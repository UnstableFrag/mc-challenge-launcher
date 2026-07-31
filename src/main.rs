use anyhow::Result;
use crossterm::event::{self, Event, KeyCode, KeyEventKind};
use clap::Parser;
use ratatui::{
    backend::CrosstermBackend,
    layout::{Alignment, Constraint, Direction, Layout},
    style::{Color, Modifier, Style},
    text::{Line, Span, Text},
    widgets::{Block, Borders, Paragraph, Wrap},
    Terminal,
};
use std::io::{self, Stdout};
use std::time::Duration;

mod modrinth;
mod instance;
mod cleanup;
mod embed;

use modrinth::ModrinthApi;
use instance::InstanceManager;
use cleanup::clean_instance;
use dirs;

#[derive(Parser)]
#[command(name = "mc-challenge-launcher")]
#[command(about = "Random modpack challenge launcher")]
struct Args {
    #[arg(long)]
    modpack: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    let args = Args::parse();
    let mut terminal = init_tui()?;
    let mut app = App::new(args.modpack).await?;
    loop {
        let _ = terminal.draw(|f| app.ui(f));
        if let Ok(true) = event::poll(Duration::from_millis(50)) {
            if let Ok(Event::Key(key)) = event::read() {
                if key.kind == KeyEventKind::Press {
                    match app.handle_key(key.code).await {
                        Ok(true) => break,
                        Err(e) => app.push_log(&e.to_string()),
                        _ => {}
                    }
                }
            }
        }
    }
    restore_tui(&mut terminal)?;
    Ok(())
}

struct App {
    state: AppState,
    api: ModrinthApi,
    instance_mgr: InstanceManager,
    current_pack: Option<modrinth::Modpack>,
    log_lines: Vec<String>,
    modpack_slug: Option<String>,
}

enum AppState { Idle, Searching, OpeningModrinth, Injecting, Done }

fn normalize_key(key: KeyCode) -> KeyCode {
    match key {
        KeyCode::Char(c) => {
            let lower = c.to_ascii_lowercase();
            let mapped = match lower {
                'r' | 'к' => 'r',
                'q' | 'й' => 'q',
                'x' | 'ч' => 'x',
                other => other,
            };
            KeyCode::Char(mapped)
        }
        other => other,
    }
}

impl App {
    async fn new(modpack_slug: Option<String>) -> Result<Self> {
        Ok(Self {
            state: AppState::Idle,
            api: ModrinthApi::new(),
            instance_mgr: InstanceManager::new()?,
            current_pack: None,
            log_lines: vec!["mc-challenge-launcher ready".into()],
            modpack_slug,
        })
    }

    fn push_log(&mut self, msg: &str) {
        let ts = chrono::Local::now().format("%H:%M:%S");
        self.log_lines.push(format!("[{}] {}", ts, msg));
        if self.log_lines.len() > 100 { self.log_lines.remove(0); }
    }

    async fn handle_key(&mut self, key: KeyCode) -> Result<bool> {
        let key = normalize_key(key);
        match (key, &self.state) {
            (KeyCode::Char('q'), _) => return Ok(true),
            (KeyCode::Char('r'), AppState::Idle) => self.start_roll().await?,
            (KeyCode::Char('r'), AppState::Done) => { self.start_roll().await?; }
            (KeyCode::Char('x'), AppState::Done) => self.cleanup().await?,
            _ => {}
        }
        Ok(false)
    }

    async fn start_roll(&mut self) -> Result<()> {
        let slug_clone = self.modpack_slug.clone();
        let pack = if let Some(slug) = &slug_clone {
            self.push_log(&format!("Using specified modpack: {}", slug));
            self.api.modpack_by_slug(slug).await?
        } else {
            self.state = AppState::Searching;
            self.push_log("Searching random modpack...");
            let pack = self.api.random_modpack().await?;
            self.push_log(&format!("Found: {}", pack.title));
            pack
        };
        self.current_pack = Some(pack.clone());

        let target_version = match pack.pick_version() {
            Some(v) => v,
            None => {
                self.push_log(&format!(
                    "Unsupported MC versions: {} (supported: {})",
                    pack.versions_str(),
                    embed::SUPPORTED_VERSIONS.join(", ")
                ));
                self.reset();
                return Ok(());
            }
        };
        self.push_log(&format!("Target MC version: {}", target_version));

        if self.modpack_slug.is_some() {
            self.state = AppState::Injecting;
            self.push_log("Downloading & extracting .mrpack...");
            let instance = match self.instance_mgr.create_instance_from_modpack(&pack.slug, self.api.client(), Some(&target_version)).await {
                Ok(inst) => inst,
                Err(e) => {
                    self.push_log(&format!("Download failed: {}", e));
                    self.reset();
                    return Ok(());
                }
            };
            self.push_log(&format!("Instance: {}", instance.path.display()));
            self.inject_mod(&instance, &target_version).await?;
        } else {
            self.state = AppState::OpeningModrinth;
            self.push_log("Opening in Modrinth App...");
            if let Err(e) = open::that(format!("modrinth://modpack/{}", pack.slug)) {
                self.push_log(&format!("Could not open Modrinth App: {}", e));
                self.push_log("Use '--modpack <slug>' for direct download mode");
                self.reset();
                return Ok(());
            }
            self.state = AppState::Injecting;
            self.push_log("Waiting for instance (up to 2 min)...");
            let instance = match self.instance_mgr.wait_for_instance(&pack.slug, &pack.title).await {
                Ok(inst) => inst,
                Err(e) => {
                    self.push_log(&e.to_string());
                    self.push_log("Install Modrinth App or use '--modpack <slug>'");
                    self.reset();
                    return Ok(());
                }
            };
            self.push_log(&format!("Instance: {}", instance.path.display()));
            self.inject_mod(&instance, &target_version).await?;
        }
        self.state = AppState::Done;
        self.push_log("Done! Launch Minecraft to start the challenge");
        Ok(())
    }

    async fn inject_mod(&mut self, instance: &instance::Instance, version: &str) -> Result<()> {
        let jar_name = embed::jar_name(version);
        let file = embed::MOD_JAR_DIR
            .get_file(&jar_name)
            .ok_or_else(|| anyhow::anyhow!("embedded mod jar missing in binary: {}", jar_name))?;
        let dest = instance.mods_dir.join(jar_name);
        std::fs::create_dir_all(&instance.mods_dir)?;
        std::fs::write(&dest, file.contents())?;
        self.push_log(&format!("Injected {}", dest.display()));
        Ok(())
    }

    async fn cleanup(&mut self) -> Result<()> {
        self.push_log("Cleaning up...");
        let slug = self.current_pack.as_ref().map(|p| p.slug.clone());
        let title = self.current_pack.as_ref().map(|p| p.title.clone());
        if let Some(ref s) = slug {
            if let Err(e) = clean_instance(s, title.as_deref().unwrap_or(s)).await {
                self.push_log(&format!("Cleanup issue: {}", e));
            }
            let work_dir = dirs::data_dir().unwrap().join("mc-challenge-launcher/instances");
            let direct = work_dir.join(s);
            if direct.exists() { std::fs::remove_dir_all(&direct).ok(); }
        }
        self.push_log("Cleaned up");
        self.reset();
        Ok(())
    }

    fn reset(&mut self) {
        self.state = AppState::Idle;
        self.current_pack = None;
    }

    fn ui(&mut self, f: &mut ratatui::Frame) {
        let chunks = Layout::default().direction(Direction::Vertical).constraints([
            Constraint::Length(3), Constraint::Min(0), Constraint::Length(8), Constraint::Length(3),
        ]).split(f.size());

        let title = match self.state {
            AppState::Idle => "IDLE — [R] to roll",
            AppState::Searching => "SEARCHING...",
            AppState::OpeningModrinth => "OPENING IN MODRINTH APP...",
            AppState::Injecting => "INJECTING...",
            AppState::Done => "DONE — Launch Minecraft!",
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
            if !pack.author.is_empty() {
                lines.push(Line::from(vec![Span::styled("👤 ", Style::default().fg(Color::Gray)), Span::raw(&pack.author)]));
            }
            if !pack.versions.is_empty() {
                lines.push(Line::from(vec![Span::styled("🎮 ", Style::default().fg(Color::Magenta)), Span::raw(pack.versions_str())]));
            }
            if !pack.categories.is_empty() {
                lines.push(Line::from(vec![Span::styled("🏷️ ", Style::default().fg(Color::Green)), Span::raw(pack.categories.join(", "))]));
            }
            if let Some(desc) = &pack.description {
                if !desc.is_empty() {
                    lines.push(Line::from(""));
                    for l in textwrap::wrap(desc, 50) {
                        lines.push(Line::from(Span::raw(format!("  {}", l))));
                    }
                }
            }
        } else {
            lines.push(Line::from("Press [R] to roll a random modpack"));
        }
        f.render_widget(
            Paragraph::new(Text::from(lines)).wrap(Wrap { trim: true })
                .block(Block::default().borders(Borders::ALL).title("Modpack")),
            main_chunks[0],
        );

        f.render_widget(
            Paragraph::new(match self.state {
                AppState::Done => "Challenge mod injected.\nLaunch Minecraft to play.\nThe mod picks a random target!",
                _ => "Waiting...",
            }).alignment(Alignment::Center).block(Block::default().borders(Borders::ALL).title("Status")),
            main_chunks[1],
        );

        f.render_widget(
            Paragraph::new(Text::from(self.log_lines.join("\n")))
                .block(Block::default().borders(Borders::ALL).title("Log")).wrap(Wrap { trim: true }),
            chunks[2],
        );
        let help = match self.state {
            AppState::Idle => "[R] Roll  [Q] Quit",
            AppState::Done => "[R] Roll again  [X] Cleanup  [Q] Quit",
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
