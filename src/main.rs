// src/main.rs
use anyhow::Result;
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use clap::Parser;
use ratatui::{
    backend::CrosstermBackend,
    layout::{Alignment, Constraint, Direction, Layout},
    style::{Color, Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, Gauge, Paragraph, Wrap},
    Terminal,
};
use std::io::{self, Stdout};
use std::time::{Duration, Instant};

mod modrinth;
mod instance;
mod challenge;
mod monitor;
mod cleanup;
mod embed;

use modrinth::ModrinthApi;
use instance::InstanceManager;
use challenge::{ChallengeConfig, ItemPool};
use monitor::Monitor;
use cleanup::clean_instance;
use dirs;

#[derive(Parser)]
#[command(name = "mc-challenge-launcher")]
#[command(about = "Random modpack challenge launcher")]
struct Args {
    /// Launch a specific modpack by slug instead of rolling randomly
    #[arg(long)]
    modpack: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let mut terminal = init_tui()?;
    let mut app = App::new(args.modpack).await?;
    loop {
        terminal.draw(|f| app.ui(f))?;
        if event::poll(Duration::from_millis(50))? {
            if let Event::Key(key) = event::read()? {
                if key.kind == KeyEventKind::Press {
                    if app.handle_key(key.code).await? { break; }
                }
            }
        }
        app.tick().await?;
    }
    restore_tui(&mut terminal)?;
    Ok(())
}

struct App {
    state: AppState,
    api: ModrinthApi,
    instance_mgr: InstanceManager,
    monitor: Monitor,
    current_pack: Option<modrinth::Modpack>,
    challenge: Option<ChallengeConfig>,
    timer_start: Option<Instant>,
    result: Option<monitor::RunResult>,
    log_lines: Vec<String>,
    auto_cleanup_timer: Option<Instant>,
    modpack_slug: Option<String>,
}

enum AppState { Idle, Searching, OpeningModrinth, Injecting, Running, Completed, AutoCleanup, Cleaning }

impl App {
    async fn new(modpack_slug: Option<String>) -> Result<Self> {
        Ok(Self {
            state: AppState::Idle,
            api: ModrinthApi::new(),
            instance_mgr: InstanceManager::new()?,
            monitor: Monitor::new(),
            current_pack: None,
            challenge: None,
            timer_start: None,
            result: None,
            log_lines: vec!["🎲 Modpack Challenge Launcher ready".into()],
            auto_cleanup_timer: None,
            modpack_slug,
        })
    }

    fn push_log(&mut self, msg: &str) {
        let ts = chrono::Local::now().format("%H:%M:%S");
        self.log_lines.push(format!("[{}] {}", ts, msg));
        if self.log_lines.len() > 100 { self.log_lines.remove(0); }
    }

    async fn handle_key(&mut self, key: KeyCode) -> Result<bool> {
        match (key, &self.state) {
            (KeyCode::Char('q'), _) => return Ok(true),
            (KeyCode::Char('r'), AppState::Idle) => self.start_roll().await?,
            (KeyCode::Char('c'), AppState::Running) => self.cancel_run().await?,
            (KeyCode::Char('x'), AppState::Completed) => self.cleanup_and_reset().await?,
            (KeyCode::Char('x'), AppState::AutoCleanup) => self.cleanup_and_reset().await?,
            (KeyCode::Char('x'), AppState::Cleaning) => self.cleanup_and_reset().await?,
            _ => {}
        }
        Ok(false)
    }

    async fn start_roll(&mut self) -> Result<()> {
        let pack = if let Some(slug) = &self.modpack_slug {
            self.push_log(&format!("🎯 Using specified modpack: {}", slug));
            self.api.modpack_by_slug(slug).await?
        } else {
            self.state = AppState::Searching;
            self.push_log("🔍 Searching random modpack...");
            let pack = self.api.random_modpack().await?;
            self.push_log(&format!("🎯 Found: {}", pack.title));
            pack
        };
        self.current_pack = Some(pack.clone());

        if self.modpack_slug.is_some() {
            self.state = AppState::Injecting;
            self.push_log("📥 Downloading & extracting .mrpack...");
            let instance = self.instance_mgr.create_instance_from_modpack(&pack.slug, self.api.client()).await?;
            self.push_log(&format!("📂 Instance: {}", instance.path.display()));
            self.push_log("💉 Injecting challenge mod...");
            self.inject_challenge(&instance).await?;
            self.state = AppState::Running;
            self.timer_start = Some(Instant::now());
            self.monitor.start(instance.path.clone());
            self.push_log("🚀 Challenge active! Get the item!");
        } else {
            self.state = AppState::OpeningModrinth;
            self.push_log("📦 Opening in Modrinth App...");
            open::that(format!("modrinth://modpack/{}", pack.slug))?;
            self.state = AppState::Injecting;
            self.push_log("⏳ Waiting for instance...");
            let instance = self.instance_mgr.wait_for_instance(&pack.slug, &pack.title).await?;
            self.push_log(&format!("📂 Instance: {}", instance.path.display()));
            self.push_log("💉 Injecting challenge mod...");
            self.inject_challenge(&instance).await?;
            self.state = AppState::Running;
            self.timer_start = Some(Instant::now());
            self.monitor.start(instance.path.clone());
            self.push_log("🚀 Challenge active! Get the item!");
        }
        Ok(())
    }

    async fn inject_challenge(&mut self, instance: &instance::Instance) -> Result<()> {
        let file = embed::MOD_JAR_DIR
            .get_file(embed::MOD_JAR_NAME)
            .ok_or_else(|| anyhow::anyhow!("embedded mod jar missing in binary"))?;
        let dest = instance.mods_dir.join("challenge-hud.jar");
        std::fs::create_dir_all(&instance.mods_dir)?;
        std::fs::write(&dest, file.contents())?;
        self.push_log(&format!("✅ Wrote {}", dest.display()));

        let pool = ItemPool::default();
        let target = pool.random();
        let challenge = ChallengeConfig::new(target.clone(), pool.items);
        challenge.write_to(&instance.config_dir)?;
        self.challenge = Some(challenge);
        self.push_log(&format!("🎲 Target: {} (5min deadline)", target));
        Ok(())
    }

    async fn cancel_run(&mut self) -> Result<()> { self.push_log("❌ Cancelled"); self.reset(); Ok(()) }

    async fn cleanup_and_reset(&mut self) -> Result<()> {
        self.state = AppState::Cleaning;
        if let Some(pack) = &self.current_pack { 
            clean_instance(&pack.slug, &pack.title).await?;
            // Also clean direct instance in work_dir
            let work_dir = dirs::data_dir().unwrap().join("mc-challenge-launcher/instances");
            let direct = work_dir.join(&pack.slug);
            if direct.exists() { std::fs::remove_dir_all(&direct).ok(); }
        }
        self.push_log("🧹 Cleaned up");
        self.reset();
        Ok(())
    }

    fn reset(&mut self) {
        self.state = AppState::Idle;
        self.current_pack = None;
        self.challenge = None;
        self.timer_start = None;
        self.result = None;
        self.monitor = Monitor::new();
        self.auto_cleanup_timer = None;
    }

    async fn tick(&mut self) -> Result<()> {
        if let AppState::Running = self.state {
            if let Some(res) = self.monitor.check_result()? {
                self.result = Some(res);
                self.state = AppState::AutoCleanup;
                self.push_log("🏁 RUN COMPLETE! Minecraft will close automatically...");
                self.auto_cleanup_timer = Some(std::time::Instant::now());
            }
        }
        if let AppState::AutoCleanup = self.state {
            if let Some(started) = self.auto_cleanup_timer {
                if started.elapsed().as_secs() >= 10 {
                    self.state = AppState::Cleaning;
                    if let Some(pack) = &self.current_pack { clean_instance(&pack.slug, &pack.title).await?; }
                    self.push_log("🧹 Cleaned up");
                    self.reset();
                }
            }
        }
        Ok(())
    }

    fn ui(&mut self, f: &mut ratatui::Frame) {
        let chunks = Layout::default().direction(Direction::Vertical).constraints([
            Constraint::Length(3), Constraint::Min(0), Constraint::Length(8), Constraint::Length(3),
        ]).split(f.size());

        let title = match self.state {
            AppState::Idle => "🎲 IDLE — Press [R] to roll",
            AppState::Searching => "🔍 SEARCHING...",
            AppState::OpeningModrinth => "📦 OPENING IN MODRINTH APP...",
            AppState::Injecting => "💉 INJECTING CHALLENGE...",
            AppState::Running => "🏃 RUNNING — Get the item!",
            AppState::AutoCleanup => "🧹 AUTO-CLEANUP — Minecraft closing...",
            AppState::Cleaning => "🧹 CLEANING...",
        };
        f.render_widget(
            Paragraph::new(title).style(Style::default().fg(Color::Cyan).add_modifier(Modifier::BOLD))
                .alignment(Alignment::Center).block(Block::default().borders(Borders::ALL)),
            chunks[0],
        );

        let main_chunks = Layout::default().direction(Direction::Horizontal)
            .constraints([Constraint::Percentage(60), Constraint::Percentage(40)]).split(chunks[1]);

        let mut lines = vec![];
        if let Some(pack) = &self.current_pack {
            lines.push(Line::from(vec![Span::styled("📦 ", Style::default().fg(Color::Yellow)), Span::raw(&pack.title)]));
            lines.push(Line::from(vec![Span::styled("👤 ", Style::default().fg(Color::Gray)), Span::raw(&pack.author)]));
            lines.push(Line::from(vec![Span::styled("🎮 ", Style::default().fg(Color::Blue)), Span::raw(pack.versions.join(", "))]));
            lines.push(Line::from(vec![Span::styled("🏷️ ", Style::default().fg(Color::Green)), Span::raw(pack.categories.join(", "))]));
            lines.push(Line::from(""));
            for l in textwrap::wrap(&pack.description.clone().unwrap_or_default(), 50) {
                lines.push(Line::from(Span::raw(format!("  {}", l))));
            }
            if let Some(ch) = &self.challenge {
                lines.push(Line::from(""));
                lines.push(Line::from(vec![Span::styled("🎯 TARGET: ", Style::default().fg(Color::Red).add_modifier(Modifier::BOLD)), Span::raw(&ch.target)]));
            }
            if let Some(start) = self.timer_start {
                let e = start.elapsed();
                lines.push(Line::from(vec![Span::styled("⏱️  TIME: ", Style::default().fg(Color::Magenta)),
                    Span::raw(format!("{:02}:{:02}.{:02}", e.as_secs()/60, e.as_secs()%60, e.subsec_millis()/10))]));
            }
            if let Some(res) = &self.result {
                lines.push(Line::from(""));
                lines.push(Line::from(vec![Span::styled("🏁 DONE! ", Style::default().fg(Color::Green).add_modifier(Modifier::BOLD)), Span::raw(&res.player)]));
                lines.push(Line::from(vec![Span::styled("   Item: ", Style::default().fg(Color::Yellow)), Span::raw(&res.item)]));
                lines.push(Line::from(vec![Span::styled("   Time: ", Style::default().fg(Color::Cyan)), Span::raw(format!("{} ticks", res.time_ticks))]));
            }
        } else {
            lines.push(Line::from("Press [R] to roll a random modpack"));
        }
        f.render_widget(
            Paragraph::new(Text::from(lines)).wrap(Wrap { trim: true })
                .block(Block::default().borders(Borders::ALL).title("Pack / Challenge")),
            main_chunks[0],
        );

        match self.state {
            AppState::Running => {
                let elapsed = self.timer_start.unwrap().elapsed().as_secs_f64();
                let gauge = Gauge::default()
                    .block(Block::default().borders(Borders::ALL).title("⏱️  Timer"))
                    .gauge_style(Style::default().fg(Color::Cyan))
                    .ratio((elapsed % 60.0) / 60.0)
                    .label(format!("{:02}:{:02}", elapsed as u64 / 60, elapsed as u64 % 60));
                f.render_widget(gauge, main_chunks[1]);
            }
            AppState::AutoCleanup => {
                let remaining = 10 - self.auto_cleanup_timer.unwrap().elapsed().as_secs();
                f.render_widget(Paragraph::new(format!("✅ Challenge complete! Closing Minecraft...\nCleanup in {}s", remaining))
                    .alignment(Alignment::Center).block(Block::default().borders(Borders::ALL).title("Result")), main_chunks[1]);
            }
            AppState::Completed => {
                f.render_widget(Paragraph::new("✅ Challenge completed!\nPress [X] to cleanup and roll again")
                    .alignment(Alignment::Center).block(Block::default().borders(Borders::ALL).title("Result")), main_chunks[1]);
            }
            _ => {
                f.render_widget(Paragraph::new("Waiting for challenge...").alignment(Alignment::Center)
                    .block(Block::default().borders(Borders::ALL).title("Status")), main_chunks[1]);
            }
        }

        f.render_widget(
            Paragraph::new(Text::from(self.log_lines.join("\n")))
                .block(Block::default().borders(Borders::ALL).title("Log")).wrap(Wrap { trim: true }),
            chunks[2],
        );
        let help = match self.state {
            AppState::Idle => "[R] Roll  [Q] Quit",
            AppState::Running => "[C] Cancel  [Q] Quit",
            AppState::AutoCleanup | AppState::Cleaning | AppState::Completed => "[X] Cleanup & Reset  [Q] Quit",
            _ => "[Q] Quit",
        };
        f.render_widget(Paragraph::new(help).alignment(Alignment::Center).block(Block::default().borders(Borders::ALL)), chunks[3]);
    }
}

fn init_tui() -> Result<Terminal<CrosstermBackend<Stdout>>> {
    crossterm::terminal::enable_raw_mode()?;
    crossterm::execute!(io::stdout(), crossterm::terminal::EnterAlternateScreen)?;
    Ok(Terminal::new(CrosstermBackend::new(io::stdout()))?)
}

fn restore_tui(terminal: &mut Terminal<CrosstermBackend<Stdout>>) -> Result<()> {
    crossterm::execute!(terminal.backend_mut(), crossterm::terminal::LeaveAlternateScreen)?;
    crossterm::terminal::disable_raw_mode()?;
    Ok(())
}