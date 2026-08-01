#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::future::Future;
use std::sync::Arc;

use clap::Parser;
use eframe::egui;
use egui::{Align, Color32, Layout, Margin, RichText, Stroke};
use tokio::sync::mpsc;

mod cleanup;
mod embed;
mod instance;
mod modrinth;

use cleanup::clean_instance;
use instance::{Instance, InstanceManager};
use modrinth::{Modpack, ModrinthApi};

use dirs;

// ---------------------------------------------------------------------------
// Palette
// ---------------------------------------------------------------------------

const ACCENT: Color32 = Color32::from_rgb(84, 200, 188);
const GREEN: Color32 = Color32::from_rgb(105, 215, 125);
const WINDOW_FILL: Color32 = Color32::from_rgb(19, 21, 25);
const CARD_FILL: Color32 = Color32::from_rgb(30, 33, 40);
const CARD_STROKE: Color32 = Color32::from_rgb(58, 63, 74);
const PANEL_FILL: Color32 = Color32::from_rgb(26, 29, 35);
const PANEL_STROKE: Color32 = Color32::from_rgb(52, 57, 67);
const MUTED_TEXT: Color32 = Color32::from_gray(180);

// ---------------------------------------------------------------------------
// CLI
// ---------------------------------------------------------------------------

#[derive(Parser)]
#[command(name = "mc-challenge-launcher")]
#[command(about = "Random modpack challenge launcher")]
struct Args {
    #[arg(long)]
    modpack: Option<String>,
}

// ---------------------------------------------------------------------------
// State
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum AppState {
    Idle,
    Searching,
    OpeningModrinth,
    Injecting,
    Done,
}

impl AppState {
    fn badge_text(self) -> &'static str {
        match self {
            AppState::Idle => "IDLE",
            AppState::Searching => "SEARCHING",
            AppState::OpeningModrinth => "OPENING MODRINTH",
            AppState::Injecting => "INJECTING",
            AppState::Done => "DONE",
        }
    }

    fn badge_color(self) -> Color32 {
        match self {
            AppState::Idle => Color32::from_rgb(140, 150, 160),
            AppState::Searching => Color32::from_rgb(235, 185, 70),
            AppState::OpeningModrinth => Color32::from_rgb(235, 150, 70),
            AppState::Injecting => Color32::from_rgb(170, 140, 255),
            AppState::Done => GREEN,
        }
    }

    /// TUI status copy, kept verbatim.
    fn status_line(self) -> &'static str {
        match self {
            AppState::Idle => "Ready",
            AppState::Searching => "SEARCHING...",
            AppState::OpeningModrinth => "OPENING IN MODRINTH APP...",
            AppState::Injecting => "INJECTING...",
            AppState::Done => "Done! Launch Minecraft to start the challenge",
        }
    }

    fn busy(self) -> bool {
        matches!(
            self,
            AppState::Searching | AppState::OpeningModrinth | AppState::Injecting
        )
    }
}

// ---------------------------------------------------------------------------
// UI <-> task messaging
// ---------------------------------------------------------------------------

enum UiMsg {
    Log(String),
    Pack(Modpack),
    State(AppState),
    /// Error text to log (empty = reset only); then the app goes back to Idle.
    Error(String),
    Done,
}

struct TaskCtx {
    tx: mpsc::UnboundedSender<UiMsg>,
    ctx: egui::Context,
}

impl TaskCtx {
    fn log(&self, msg: impl Into<String>) {
        let _ = self.tx.send(UiMsg::Log(msg.into()));
        self.ctx.request_repaint();
    }

    fn pack(&self, pack: Modpack) {
        let _ = self.tx.send(UiMsg::Pack(pack));
        self.ctx.request_repaint();
    }

    fn state(&self, state: AppState) {
        let _ = self.tx.send(UiMsg::State(state));
        self.ctx.request_repaint();
    }

    fn error(&self, msg: impl Into<String>) {
        let _ = self.tx.send(UiMsg::Error(msg.into()));
        self.ctx.request_repaint();
    }

    fn done(&self) {
        let _ = self.tx.send(UiMsg::Done);
        self.ctx.request_repaint();
    }
}

// ---------------------------------------------------------------------------
// App
// ---------------------------------------------------------------------------

struct App {
    state: AppState,
    /// True while a roll/cleanup task is running (also disables buttons).
    busy: bool,
    current_pack: Option<Modpack>,
    /// Increments on every new pack so entrance animations replay per roll.
    pack_seq: u64,
    log_lines: Vec<String>,
    modpack_slug: Option<String>,
    tx: mpsc::UnboundedSender<UiMsg>,
    rx: mpsc::UnboundedReceiver<UiMsg>,
    egui_ctx: egui::Context,
}

impl App {
    fn new(modpack_slug: Option<String>, egui_ctx: egui::Context) -> Self {
        let (tx, rx) = mpsc::unbounded_channel();
        Self {
            state: AppState::Idle,
            busy: false,
            current_pack: None,
            pack_seq: 0,
            log_lines: vec!["mc-challenge-launcher ready".to_owned()],
            modpack_slug,
            tx,
            rx,
            egui_ctx,
        }
    }

    fn push_log(&mut self, msg: &str) {
        let ts = chrono::Local::now().format("%H:%M:%S");
        self.log_lines.push(format!("[{}] {}", ts, msg));
        if self.log_lines.len() > 100 {
            self.log_lines.remove(0);
        }
    }

    fn drain_messages(&mut self) {
        while let Ok(msg) = self.rx.try_recv() {
            match msg {
                UiMsg::Log(s) => self.push_log(&s),
                UiMsg::Pack(p) => {
                    self.pack_seq += 1;
                    self.current_pack = Some(p);
                }
                UiMsg::State(s) => self.state = s,
                UiMsg::Error(e) => {
                    if !e.is_empty() {
                        self.push_log(&e);
                    }
                    self.state = AppState::Idle;
                    self.current_pack = None;
                    self.busy = false;
                }
                UiMsg::Done => {
                    self.state = AppState::Done;
                    self.busy = false;
                }
            }
        }
    }

    fn start_roll(&mut self) {
        if self.busy {
            return;
        }
        self.busy = true;
        let tx = self.tx.clone();
        let ctx = self.egui_ctx.clone();
        let slug = self.modpack_slug.clone();
        spawn_async_op(move || {
            let tc = TaskCtx { tx, ctx };
            async move {
                let mgr = match InstanceManager::new() {
                    Ok(m) => m,
                    Err(e) => {
                        tc.error(e.to_string());
                        return;
                    }
                };
                let api = ModrinthApi::new();
                run_roll(api, mgr, slug, tc).await;
            }
        });
    }

    fn start_cleanup(&mut self) {
        if self.busy {
            return;
        }
        let Some(pack) = self.current_pack.clone() else {
            return;
        };
        self.busy = true;
        let tx = self.tx.clone();
        let ctx = self.egui_ctx.clone();
        spawn_async_op(move || {
            let tc = TaskCtx { tx, ctx };
            async move {
                run_cleanup(pack, tc).await;
            }
        });
    }

    fn quit(&self, ctx: &egui::Context) {
        ctx.send_viewport_cmd(egui::ViewportCommand::Close);
    }

    fn handle_shortcuts(&mut self, ctx: &egui::Context) {
        let roll = ctx.input(|i| i.key_pressed(egui::Key::R));
        let clean = ctx.input(|i| i.key_pressed(egui::Key::X));
        let quit = ctx.input(|i| i.key_pressed(egui::Key::Q))
            || ctx.input(|i| i.key_pressed(egui::Key::Escape));
        if quit {
            self.quit(ctx);
        } else if roll && !self.busy && matches!(self.state, AppState::Idle | AppState::Done) {
            self.start_roll();
        } else if clean && !self.busy && matches!(self.state, AppState::Done) {
            self.start_cleanup();
        }
    }

    // -- top bar ------------------------------------------------------------

    fn state_badge(&self, ui: &mut egui::Ui) {
        let color = self.state.badge_color();
        egui::Frame::new()
            .fill(color.gamma_multiply(0.16))
            .stroke(Stroke::new(1.0, color.gamma_multiply(0.9)))
            .corner_radius(10)
            .inner_margin(Margin::symmetric(8, 3))
            .show(ui, |ui| {
                ui.label(RichText::new(self.state.badge_text()).color(color).strong().small());
            });
    }

    // -- main column --------------------------------------------------------

    fn pack_panel(&self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        if let Some(pack) = &self.current_pack {
            let t = ctx.animate_bool(egui::Id::new(("pack_card", self.pack_seq)), true);
            ui.set_opacity(t);
            egui::Frame::new()
                .fill(CARD_FILL)
                .stroke(Stroke::new(1.0, CARD_STROKE))
                .corner_radius(8)
                .inner_margin(Margin::same(12))
                .show(ui, |ui| {
                    ui.horizontal(|ui| {
                        ui.add_space((1.0 - t) * 18.0);
                        ui.vertical(|ui| {
                            self.pack_card(ui, pack);
                        });
                    });
                });
        } else {
            egui::Frame::new()
                .fill(CARD_FILL)
                .stroke(Stroke::new(1.0, CARD_STROKE))
                .corner_radius(8)
                .inner_margin(Margin::same(16))
                .show(ui, |ui| {
                    ui.add_space(6.0);
                    ui.label(RichText::new("Press [R] to roll a random modpack").strong().size(16.0));
                    ui.label(RichText::new("or click Roll").weak().small());
                    ui.add_space(12.0);
                    ui.separator();
                    ui.add_space(8.0);
                    ui.label(
                        RichText::new(format!("Supported: {}", embed::support_summary()))
                            .weak()
                            .small(),
                    );
                });
        }
    }

    fn pack_card(&self, ui: &mut egui::Ui, pack: &Modpack) {
        ui.label(RichText::new(&pack.title).strong().size(20.0).color(ACCENT));
        if !pack.author.is_empty() {
            ui.add_space(2.0);
            ui.label(RichText::new(format!("by {}", pack.author)).weak().small());
        }
        ui.add_space(8.0);
        if !pack.versions.is_empty() {
            field_row(ui, "MC", &pack.versions_str(), ACCENT);
        }
        if let Some(loader) = pack.primary_loader() {
            field_row(ui, "Loader", loader.display_name(), Color32::from_rgb(170, 140, 255));
        }
        if !pack.categories.is_empty() {
            ui.add_space(4.0);
            ui.horizontal_wrapped(|ui| {
                for cat in &pack.categories {
                    chip(ui, cat);
                }
            });
        }
        if let Some(desc) = &pack.description {
            if !desc.is_empty() {
                ui.add_space(10.0);
                ui.separator();
                ui.add_space(6.0);
                ui.label(RichText::new(desc).small());
            }
        }
    }

    // -- status panel -------------------------------------------------------

    fn status_panel(&mut self, ui: &mut egui::Ui) {
        let ctx = ui.ctx().clone();
        egui::Frame::new()
            .fill(PANEL_FILL)
            .stroke(Stroke::new(1.0, PANEL_STROKE))
            .corner_radius(8)
            .inner_margin(Margin::same(12))
            .show(ui, |ui| {
                ui.label(RichText::new("Status").strong());
                ui.add_space(10.0);

                if self.state.busy() {
                    ui.label(
                        RichText::new(self.state.status_line())
                            .strong()
                            .color(self.state.badge_color()),
                    );
                    ui.add_space(12.0);
                    let phase = (ui.ctx().input(|i| i.time) as f32 * 0.6).fract();
                    ui.add(
                        egui::ProgressBar::new(phase)
                            .animate(true)
                            .fill(ACCENT)
                            .desired_width(ui.available_width().min(320.0)),
                    );
                    ui.add_space(8.0);
                    shimmer_strip(ui);
                } else {
                    match self.state {
                        AppState::Idle => {
                            ui.label(
                                RichText::new("Ready")
                                    .strong()
                                    .color(Color32::from_rgb(140, 150, 160)),
                            );
                            ui.add_space(4.0);
                            ui.label(RichText::new("Press [R] to roll a random modpack").weak().small());
                        }
                        AppState::Done => {
                            let (rect, _) =
                                ui.allocate_exact_size(egui::vec2(56.0, 56.0), egui::Sense::hover());
                            done_pulse(&ctx, &ui.painter(), rect, egui::Id::new(("done_entrance", self.pack_seq)));
                            ui.add_space(6.0);
                            ui.label(RichText::new("Done! Launch Minecraft to start the challenge").strong().color(GREEN));
                            ui.add_space(4.0);
                            ui.label(
                                RichText::new(
                                    "Challenge mod injected.\nLaunch Minecraft to play.\nThe mod picks a random target!",
                                )
                                .weak()
                                .small(),
                            );
                        }
                        AppState::Searching | AppState::OpeningModrinth | AppState::Injecting => {
                            // Handled by the busy() branch above.
                        }
                    }
                }

                ui.add_space(14.0);
                ui.separator();
                ui.add_space(8.0);
                ui.horizontal(|ui| {
                    let can_roll = !self.busy && matches!(self.state, AppState::Idle | AppState::Done);
                    if ui.add_enabled(can_roll, egui::Button::new("🎲 Roll")).clicked() {
                        self.start_roll();
                    }
                    let can_clean =
                        !self.busy && matches!(self.state, AppState::Done) && self.current_pack.is_some();
                    if ui.add_enabled(can_clean, egui::Button::new("🧹 Cleanup")).clicked() {
                        self.start_cleanup();
                    }
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        if ui.small_button("✕ Quit").clicked() {
                            self.quit(&ctx);
                        }
                    });
                });
            });
    }

    // -- log + hints --------------------------------------------------------

    fn help_hint(&self) -> &'static str {
        match self.state {
            AppState::Idle => "[R] Roll  [Q] Quit",
            AppState::Done => "[R] Roll again  [X] Cleanup  [Q] Quit",
            _ => "[Q] Quit",
        }
    }
}

impl eframe::App for App {
    fn ui(&mut self, ui: &mut egui::Ui, _frame: &mut eframe::Frame) {
        self.drain_messages();
        let ctx = ui.ctx().clone();
        self.handle_shortcuts(&ctx);

        egui::Frame::new()
            .fill(WINDOW_FILL)
            .inner_margin(Margin::same(10))
            .show(ui, |ui| {
                // Top bar: app name + state badge (+ right-aligned subtitle).
                ui.horizontal(|ui| {
                    ui.label(RichText::new("mc-challenge-launcher").strong().size(18.0));
                    ui.add_space(10.0);
                    self.state_badge(ui);
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new("Random modpack challenge launcher").weak().small());
                    });
                });
                ui.add_space(6.0);
                ui.separator();
                ui.add_space(6.0);

                // Main row: modpack card (60%) + status panel (40%).
                let avail = ui.available_size();
                let spacing = ui.spacing().item_spacing.x;
                let total_w = avail.x.max(320.0);
                let left_w =
                    (total_w * 0.60 - spacing * 0.5).clamp(220.0, (total_w - spacing - 160.0).max(220.0));
                let right_w = (total_w - left_w - spacing).max(160.0);
                let main_h = if avail.y > 260.0 {
                    (avail.y * 0.60).clamp(150.0, avail.y - 90.0)
                } else {
                    avail.y * 0.5
                };

                ui.horizontal(|ui| {
                    ui.allocate_ui_with_layout(egui::vec2(left_w, main_h), Layout::top_down(Align::Min), |ui| {
                        self.pack_panel(ui);
                    });
                    ui.allocate_ui_with_layout(egui::vec2(right_w, main_h), Layout::top_down(Align::Min), |ui| {
                        self.status_panel(ui);
                    });
                });

                // Log panel.
                ui.add_space(6.0);
                ui.separator();
                ui.add_space(4.0);
                ui.horizontal(|ui| {
                    ui.label(RichText::new("Log").strong());
                    ui.with_layout(Layout::right_to_left(Align::Center), |ui| {
                        ui.label(RichText::new(self.help_hint()).weak().small());
                    });
                });
                ui.add_space(2.0);

                let log_h = (ui.available_size().y - 4.0).max(60.0);
                ui.allocate_ui_with_layout(egui::vec2(total_w, log_h), Layout::top_down(Align::Min), |ui| {
                    egui::ScrollArea::vertical()
                        .auto_shrink([false, false])
                        .stick_to_bottom(true)
                        .show(ui, |ui| {
                            for line in &self.log_lines {
                                ui.monospace(RichText::new(line.as_str()).color(MUTED_TEXT));
                            }
                        });
                });
            });

        // Keep repainting while there is something animated on screen.
        if self.busy || self.state == AppState::Done {
            ctx.request_repaint();
        }
    }
}

// ---------------------------------------------------------------------------
// Async ops (mirror the TUI flow and copy exactly)
// ---------------------------------------------------------------------------

/// Runs an async op on a dedicated background thread with its own
/// current-thread tokio runtime.
///
/// A plain `tokio::spawn` is not possible here: `modrinth.rs` (read-only)
/// keeps a non-`Send` `rand::thread_rng()` across `.await` points, so the roll
/// future is not `Send`. The op closure captures only `Send` values and builds
/// the future *inside* the thread, so nothing `!Send` ever crosses a thread
/// boundary.
fn spawn_async_op<F, Fut>(op: F)
where
    F: FnOnce() -> Fut + Send + 'static,
    Fut: Future<Output = ()> + 'static,
{
    std::thread::spawn(move || {
        let rt = match tokio::runtime::Builder::new_current_thread()
            .enable_all()
            .build()
        {
            Ok(rt) => rt,
            Err(e) => {
                eprintln!("mc-challenge-launcher: failed to start async runtime: {e}");
                return;
            }
        };
        rt.block_on(op());
    });
}

async fn run_roll(api: ModrinthApi, mgr: InstanceManager, slug_opt: Option<String>, tc: TaskCtx) {
    let slug_clone = slug_opt.clone();
    let pack = if let Some(slug) = &slug_clone {
        tc.log(format!("Using specified modpack: {}", slug));
        match api.modpack_by_slug(slug).await {
            Ok(pack) => pack,
            Err(e) => {
                tc.error(e.to_string());
                return;
            }
        }
    } else {
        tc.state(AppState::Searching);
        tc.log("Searching random modpack...");
        match api.random_modpack().await {
            Ok(pack) => {
                tc.log(format!("Found: {}", pack.title));
                pack
            }
            Err(e) => {
                tc.error(e.to_string());
                return;
            }
        }
    };
    tc.pack(pack.clone());

    let target = match pack.pick_target() {
        Some(target) => target,
        None => {
            tc.log(format!(
                "Unsupported: MC versions [{}], loaders [{}] — supported: {}",
                pack.versions_str(),
                pack.loaders_str(),
                embed::support_summary()
            ));
            tc.error("");
            return;
        }
    };
    tc.log(format!(
        "Target: MC {} / {} (jar: {})",
        target.version,
        target.loader.display_name(),
        embed::jar_name(&target)
    ));

    if slug_opt.is_some() {
        tc.state(AppState::Injecting);
        tc.log("Downloading & extracting .mrpack...");
        let instance = match mgr
            .create_instance_from_modpack(&pack.slug, api.client(), Some(&target))
            .await
        {
            Ok(inst) => inst,
            Err(e) => {
                tc.log(format!("Download failed: {}", e));
                tc.error("");
                return;
            }
        };
        tc.log(format!("Instance: {}", instance.path.display()));
        inject_mod(&instance, &target, &tc);
    } else {
        tc.state(AppState::OpeningModrinth);
        tc.log("Opening in Modrinth App...");
        if let Err(e) = open::that(format!("modrinth://modpack/{}", pack.slug)) {
            tc.log(format!("Could not open Modrinth App: {}", e));
            tc.log("Use '--modpack <slug>' for direct download mode");
            tc.error("");
            return;
        }
        tc.state(AppState::Injecting);
        tc.log("Waiting for instance (up to 2 min)...");
        let instance = match mgr.wait_for_instance(&pack.slug, &pack.title).await {
            Ok(inst) => inst,
            Err(e) => {
                tc.log(e.to_string());
                tc.log("Install Modrinth App or use '--modpack <slug>'");
                tc.error("");
                return;
            }
        };
        tc.log(format!("Instance: {}", instance.path.display()));
        inject_mod(&instance, &target, &tc);
    }

    tc.log("Done! Launch Minecraft to start the challenge");
    tc.done();
}

fn inject_mod(instance: &Instance, target: &embed::Target, tc: &TaskCtx) {
    let jar_name = embed::jar_name(target);
    let Some(file) = embed::MOD_JAR_DIR.get_file(&jar_name) else {
        tc.error(format!("embedded mod jar missing in binary: {}", jar_name));
        return;
    };
    let dest = instance.mods_dir.join(jar_name);
    if let Err(e) = std::fs::create_dir_all(&instance.mods_dir) {
        tc.error(e.to_string());
        return;
    }
    if let Err(e) = std::fs::write(&dest, file.contents()) {
        tc.error(e.to_string());
        return;
    }
    tc.log(format!(
        "Injected {} (MC {}, {})",
        dest.display(),
        target.version,
        target.loader.display_name()
    ));
}

async fn run_cleanup(pack: Modpack, tc: TaskCtx) {
    tc.log("Cleaning up...");
    let slug = pack.slug.clone();
    let title = pack.title.clone();
    if let Err(e) = clean_instance(&slug, &title).await {
        tc.log(format!("Cleanup issue: {}", e));
    }
    let work_dir = dirs::data_dir().unwrap().join("mc-challenge-launcher/instances");
    let direct = work_dir.join(&slug);
    if direct.exists() {
        std::fs::remove_dir_all(&direct).ok();
    }
    tc.log("Cleaned up");
    tc.error("");
}

// ---------------------------------------------------------------------------
// Small widgets
// ---------------------------------------------------------------------------

fn field_row(ui: &mut egui::Ui, key: &str, value: &str, color: Color32) {
    ui.horizontal(|ui| {
        ui.label(RichText::new(key).strong().color(color).small());
        ui.label(RichText::new(value).small());
    });
}

fn chip(ui: &mut egui::Ui, label: &str) {
    egui::Frame::new()
        .fill(Color32::from_gray(46))
        .corner_radius(4)
        .inner_margin(Margin::symmetric(6, 2))
        .show(ui, |ui| {
            ui.label(RichText::new(label).color(Color32::from_rgb(150, 200, 235)).small());
        });
}

/// Thin animated shimmer strip below the progress bar.
fn shimmer_strip(ui: &mut egui::Ui) {
    let time = ui.ctx().input(|i| i.time) as f32;
    let width = ui.available_width().min(320.0);
    let (rect, _) = ui.allocate_exact_size(egui::vec2(width, 6.0), egui::Sense::hover());
    let painter = ui.painter();
    let radius = egui::CornerRadius::same(3);
    painter.rect_filled(rect, radius, Color32::from_gray(42));

    let hl_w = width * 0.30;
    let span = width + hl_w;
    let x = rect.left() + (time * 0.55).fract() * span - hl_w;
    let top = rect.top();
    let h = rect.height();
    painter.rect_filled(
        egui::Rect::from_min_size(egui::pos2(x, top), egui::vec2(hl_w, h)),
        radius,
        ACCENT,
    );
    let edge_w = hl_w * 0.25;
    painter.rect_filled(
        egui::Rect::from_min_size(egui::pos2(x - edge_w, top), egui::vec2(edge_w, h)),
        radius,
        ACCENT.gamma_multiply(0.30),
    );
    painter.rect_filled(
        egui::Rect::from_min_size(egui::pos2(x + hl_w, top), egui::vec2(edge_w, h)),
        radius,
        ACCENT.gamma_multiply(0.30),
    );
}

/// Green pulse + animated checkmark on the Done state.
fn done_pulse(ctx: &egui::Context, painter: &egui::Painter, rect: egui::Rect, entrance_id: egui::Id) {
    let time = ctx.input(|i| i.time) as f32;
    let entrance = ctx.animate_bool(entrance_id, true);
    let pulse = 0.5 + 0.5 * (time * 3.0).sin();
    let c = rect.center();
    let r = 16.0 + 3.0 * pulse;

    painter.circle_filled(c, r * entrance, GREEN.gamma_multiply(0.12 + 0.18 * pulse));
    painter.circle_stroke(c, r * entrance, Stroke::new(2.0, GREEN.gamma_multiply(0.55 + 0.45 * pulse)));

    let s = entrance;
    let a = c + egui::vec2(-7.0 * s, -1.0 * s);
    let b = c + egui::vec2(-2.0 * s, 4.0 * s);
    let d = c + egui::vec2(8.0 * s, -6.0 * s);
    painter.line_segment([a, b], Stroke::new(2.5, GREEN.gamma_multiply(s)));
    painter.line_segment([b, d], Stroke::new(2.5, GREEN.gamma_multiply(s)));
}

// ---------------------------------------------------------------------------
// Fonts (Cyrillic fallback for Russian modpack titles/descriptions)
// ---------------------------------------------------------------------------

fn setup_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    let candidates = ["C:\\Windows\\Fonts\\segoeui.ttf", "C:\\Windows\\Fonts\\arial.ttf"];
    for path in candidates {
        if let Ok(bytes) = std::fs::read(path) {
            let name = "cyrillic-fallback".to_owned();
            fonts
                .font_data
                .insert(name.clone(), Arc::new(egui::FontData::from_owned(bytes)));
            fonts
                .families
                .entry(egui::FontFamily::Proportional)
                .or_default()
                .push(name.clone());
            fonts
                .families
                .entry(egui::FontFamily::Monospace)
                .or_default()
                .push(name);
            break;
        }
    }
    ctx.set_fonts(fonts);
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

#[tokio::main]
async fn main() -> eframe::Result {
    let args = Args::parse();

    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("mc-challenge-launcher")
            .with_inner_size([780.0, 620.0])
            .with_min_inner_size([640.0, 480.0]),
        ..Default::default()
    };

    eframe::run_native(
        "mc-challenge-launcher",
        options,
        Box::new(move |cc| {
            setup_fonts(&cc.egui_ctx);
            Ok(Box::new(App::new(args.modpack.clone(), cc.egui_ctx.clone())))
        }),
    )
}
