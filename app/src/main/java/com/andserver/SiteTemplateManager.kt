package com.andserver

import java.io.File

/**
 * Gerenciador responsável por provisionar os arquivos do template padrão moderno
 * na pasta de hospedagem estática (/hosted/www).
 */
object SiteTemplateManager {

    fun ensureSiteTemplate(hostedDir: File) {
        val wwwDir = File(hostedDir, "www")
        if (!wwwDir.exists() || (wwwDir.listFiles()?.isEmpty() == true)) {
            createDefaultSiteFiles(hostedDir, overwrite = false)
        }
    }

    fun createDefaultSiteFiles(hostedDir: File, overwrite: Boolean) {
        val wwwDir = File(hostedDir, "www").apply { mkdirs() }
        val indexFile = File(wwwDir, "index.html")
        val styleFile = File(wwwDir, "style.css")
        val appJs = File(wwwDir, "app.js")

        if (overwrite || !indexFile.exists()) {
            indexFile.writeText(INDEX_HTML)
        }
        if (overwrite || !styleFile.exists()) {
            styleFile.writeText(STYLE_CSS)
        }
        if (overwrite || !appJs.exists()) {
            appJs.writeText(APP_JS)
        }
    }

    private val INDEX_HTML = """<!DOCTYPE html>
<html lang="pt-BR">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="theme-color" content="#0b0f17">
    <title>AndServer · Web Host</title>
    <link rel="stylesheet" href="style.css">
</head>
<body>
    <div class="ambient-glow"></div>

    <div class="page-wrapper">
        <!-- Barra de Navegação Clean -->
        <header class="navbar">
            <div class="brand">
                <div class="brand-icon">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round">
                        <rect x="2" y="2" width="20" height="8" rx="2" ry="2"></rect>
                        <rect x="2" y="14" width="20" height="8" rx="2" ry="2"></rect>
                        <line x1="6" y1="6" x2="6.01" y2="6"></line>
                        <line x1="6" y1="18" x2="6.01" y2="18"></line>
                    </svg>
                </div>
                <div class="brand-text">
                    <span class="brand-name">AndServer</span>
                    <span class="brand-pill">Web Engine</span>
                </div>
            </div>

            <div class="nav-actions">
                <div class="status-indicator" id="serverLiveStatus">
                    <span class="pulse-dot"></span>
                    <span class="status-label">Servidor Online</span>
                </div>
                <button id="themeToggle" class="btn-icon" title="Alternar Tema Claro/Escuro" aria-label="Alternar tema">
                    <svg id="themeIconSun" class="icon-theme" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <circle cx="12" cy="12" r="5"></circle>
                        <line x1="12" y1="1" x2="12" y2="3"></line>
                        <line x1="12" y1="21" x2="12" y2="23"></line>
                        <line x1="4.22" y1="4.22" x2="5.64" y2="5.64"></line>
                        <line x1="18.36" y1="18.36" x2="19.78" y2="19.78"></line>
                        <line x1="1" y1="12" x2="3" y2="12"></line>
                        <line x1="21" y1="12" x2="23" y2="12"></line>
                        <line x1="4.22" y1="19.78" x2="5.64" y2="18.36"></line>
                        <line x1="18.36" y1="5.64" x2="19.78" y2="4.22"></line>
                    </svg>
                    <svg id="themeIconMoon" class="icon-theme hidden" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                        <path d="M21 12.79A9 9 0 1 1 11.21 3 7 7 0 0 0 21 12.79z"></path>
                    </svg>
                </button>
            </div>
        </header>

        <!-- Hero Minimalista -->
        <main class="content-container">
            <section class="hero-section">
                <div class="eyebrow-badge">
                    <span class="badge-dot"></span>
                    <span>Hospedagem Web Nativa no Android</span>
                </div>
                <h1 class="hero-title">Site Moderno &amp; Clean em Alta Performance</h1>
                <p class="hero-subtitle">
                    Esta página está sendo servida diretamente da memória deste dispositivo móvel, protegida por criptografia TLS 1.3 de ponta a ponta e zero dependências externas.
                </p>

                <!-- Barra com detalhes de conexão ativa -->
                <div class="host-pill">
                    <div class="host-item">
                        <span class="host-label">HOST / ENDEREÇO</span>
                        <span class="host-value" id="currentHost">Detectando...</span>
                    </div>
                    <div class="host-divider"></div>
                    <div class="host-item">
                        <span class="host-label">PROTOCOLO</span>
                        <span class="host-value accent-cyan" id="currentProto">HTTPS / TLS 1.3</span>
                    </div>
                    <div class="host-divider"></div>
                    <div class="host-item">
                        <span class="host-label">LATÊNCIA MÉDIA</span>
                        <span class="host-value accent-mint" id="currentPing">-- ms</span>
                    </div>
                </div>

                <div class="cta-actions">
                    <a href="#playground" class="btn btn-primary">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="btn-svg">
                            <polygon points="5 3 19 12 5 21 5 3"></polygon>
                        </svg>
                        Testar API Nativa
                    </a>
                    <a href="/public/" class="btn btn-secondary" target="_blank" rel="noopener">
                        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="btn-svg">
                            <path d="M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z"></path>
                        </svg>
                        Explorador de Arquivos
                    </a>
                </div>
            </section>

            <!-- Cards de Telemetria / Métricas do Servidor -->
            <section class="metrics-grid">
                <div class="metric-card">
                    <div class="metric-header">
                        <span class="metric-title">ESTADO DO NÓ</span>
                        <div class="metric-icon-box mint">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14"></path>
                                <polyline points="22 4 12 14.01 9 11.01"></polyline>
                            </svg>
                        </div>
                    </div>
                    <div class="metric-val" id="metricStatus">Ativo &amp; Estável</div>
                    <div class="metric-meta">Disponível em rede local &amp; WAN</div>
                </div>

                <div class="metric-card">
                    <div class="metric-header">
                        <span class="metric-title">SEGURANÇA</span>
                        <div class="metric-icon-box cyan">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                                <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                            </svg>
                        </div>
                    </div>
                    <div class="metric-val">TLS 1.3 / X.509</div>
                    <div class="metric-meta">Android Keystore Isolado</div>
                </div>

                <div class="metric-card">
                    <div class="metric-header">
                        <span class="metric-title">ARQUITETURA</span>
                        <div class="metric-icon-box purple">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <path d="M16.5 9.4 7.55 4.24a1.78 1.78 0 0 0-2.5 1.55v12.42a1.78 1.78 0 0 0 2.5 1.55L16.5 14.6a1.78 1.78 0 0 0 0-3.2z"></path>
                            </svg>
                        </div>
                    </div>
                    <div class="metric-val">Kotlin Nativo</div>
                    <div class="metric-meta">0 dependências externas</div>
                </div>

                <div class="metric-card">
                    <div class="metric-header">
                        <span class="metric-title">CACHE &amp; ASSETS</span>
                        <div class="metric-icon-box amber">
                            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                                <polygon points="13 2 3 14 12 14 11 22 21 10 12 10 13 2"></polygon>
                            </svg>
                        </div>
                    </div>
                    <div class="metric-val">ETag + 32KB</div>
                    <div class="metric-meta">Streaming de baixa latência</div>
                </div>
            </section>

            <!-- Grid de Recursos e Funcionalidades -->
            <section class="features-section">
                <div class="section-heading">
                    <span class="section-tag">CARACTERÍSTICAS</span>
                    <h2 class="section-title">Construído para Eficiência Absoluta</h2>
                    <p class="section-desc">Um servidor completo projetado desde o primeiro byte para ser seguro, leve e econômico.</p>
                </div>

                <div class="features-grid">
                    <div class="feature-card">
                        <div class="feature-icon">⚡</div>
                        <h3>Desempenho Instantâneo</h3>
                        <p>Respostas em tempo recorde com conexões HTTP Keep-Alive persistentes e validação inteligente de cache ETag.</p>
                    </div>

                    <div class="feature-card">
                        <div class="feature-icon">📱</div>
                        <h3>Design 100% Responsivo</h3>
                        <p>Layout limpo e fluido que se adapta automaticamente a smartphones, tablets dobráveis e monitores ultrawide.</p>
                    </div>

                    <div class="feature-card">
                        <div class="feature-icon">🛡️</div>
                        <h3>Defesa &amp; Firewall Integrado</h3>
                        <p>Proteção automática contra ataques de força bruta, controle de concorrência e certificados assinados dinamicamente.</p>
                    </div>

                    <div class="feature-card">
                        <div class="feature-icon">📂</div>
                        <h3>Totalmente Customizável</h3>
                        <p>Coloque seus arquivos HTML, CSS, JavaScript ou Single Page Application (SPA) na pasta <code>/hosted/www</code>.</p>
                    </div>
                </div>
            </section>

            <!-- Console Interativo de API -->
            <section id="playground" class="playground-section">
                <div class="section-heading">
                    <span class="section-tag">PLAYGROUND INTERATIVO</span>
                    <h2 class="section-title">Console de Testes de API</h2>
                    <p class="section-desc">Interaja em tempo real com os endpoints do servidor Android nativo:</p>
                </div>

                <div class="console-box">
                    <div class="console-toolbar">
                        <div class="console-actions">
                            <button id="btnHealth" class="chip active">GET /health</button>
                            <button id="btnStatus" class="chip">GET /api/status</button>
                            <button id="btnFiles" class="chip">GET /api/files</button>
                        </div>
                        <div class="console-meta">
                            <span id="responseStatus" class="response-status">200 OK</span>
                            <span id="responseTimer" class="response-timer">0 ms</span>
                            <button id="btnCopy" class="btn-copy" title="Copiar resposta">
                                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="copy-svg">
                                    <rect x="9" y="9" width="13" height="13" rx="2" ry="2"></rect>
                                    <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"></path>
                                </svg>
                                <span id="copyText">Copiar</span>
                            </button>
                        </div>
                    </div>
                    <pre id="apiOutput" class="code-view"><code>Clique em uma das rotas acima para testar a comunicação em tempo real...</code></pre>
                </div>
            </section>

            <!-- Como Personalizar -->
            <section class="guide-section">
                <div class="guide-card">
                    <div class="guide-step-num">1</div>
                    <div class="guide-content">
                        <h4>Acesse o Painel Web ou App</h4>
                        <p>No aplicativo Android ou pelo navegador em <code>/</code>, abra o gerenciador de arquivos.</p>
                    </div>
                </div>
                <div class="guide-card">
                    <div class="guide-step-num">2</div>
                    <div class="guide-content">
                        <h4>Edite a Pasta <code>/hosted/www</code></h4>
                        <p>Substitua este <code>index.html</code>, adicione folhas de estilo, fontes e assets do seu projeto.</p>
                    </div>
                </div>
                <div class="guide-card">
                    <div class="guide-step-num">3</div>
                    <div class="guide-content">
                        <h4>Compartilhe com o Mundo</h4>
                        <p>Utilize a rede Wi-Fi local ou o modo Internet (WAN) para acessar seu site de qualquer lugar.</p>
                    </div>
                </div>
            </section>
        </main>

        <!-- Footer Clean -->
        <footer class="footer">
            <div class="footer-inner">
                <div class="footer-brand">
                    <span class="footer-title">AndServer</span>
                    <span class="footer-sub">Android Native Web Server</span>
                </div>
                <div class="footer-security">
                    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" class="lock-svg">
                        <rect x="3" y="11" width="18" height="11" rx="2" ry="2"></rect>
                        <path d="M7 11V7a5 5 0 0 1 10 0v4"></path>
                    </svg>
                    <span>Conexão Local Segura &amp; Criptografada</span>
                </div>
            </div>
        </footer>
    </div>

    <script src="app.js"></script>
</body>
</html>
""".trimIndent()

    private val STYLE_CSS = """/* ==========================================================================
   AndServer - Modern & Clean Layout Design System
   ========================================================================== */

:root {
    /* Paleta Dark (Padrão) */
    --bg: #090d16;
    --surface: #0f172a;
    --surface-card: #141f36;
    --surface-card-hover: #1a2845;
    --surface-glass: rgba(15, 23, 42, 0.75);
    --border: rgba(255, 255, 255, 0.08);
    --border-hover: rgba(56, 189, 248, 0.35);
    --text-primary: #f8fafc;
    --text-muted: #94a3b8;
    --text-subtle: #64748b;
    --primary: #10b981;
    --primary-glow: rgba(16, 185, 129, 0.25);
    --accent-cyan: #38bdf8;
    --accent-purple: #a855f7;
    --accent-amber: #f59e0b;
    --card-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.5);
    --radius-sm: 8px;
    --radius-md: 14px;
    --radius-lg: 20px;
    --radius-pill: 9999px;
    --transition: all 0.25s cubic-bezier(0.16, 1, 0.3, 1);
}

[data-theme="light"] {
    /* Paleta Light (Clean) */
    --bg: #f8fafc;
    --surface: #ffffff;
    --surface-card: #f1f5f9;
    --surface-card-hover: #e2e8f0;
    --surface-glass: rgba(255, 255, 255, 0.85);
    --border: rgba(0, 0, 0, 0.08);
    --border-hover: rgba(2, 132, 199, 0.35);
    --text-primary: #0f172a;
    --text-muted: #475569;
    --text-subtle: #94a3b8;
    --primary: #059669;
    --primary-glow: rgba(5, 150, 105, 0.15);
    --accent-cyan: #0284c7;
    --accent-purple: #7c3aed;
    --accent-amber: #d97706;
    --card-shadow: 0 10px 30px -10px rgba(0, 0, 0, 0.07);
}

/* Base & Reset */
* {
    box-sizing: border-box;
    margin: 0;
    padding: 0;
}

html {
    scroll-behavior: smooth;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
}

body {
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
    background-color: var(--bg);
    color: var(--text-primary);
    line-height: 1.6;
    min-height: 100vh;
    position: relative;
    overflow-x: hidden;
    transition: background-color 0.3s ease, color 0.3s ease;
}

/* Ambient Glow Background */
.ambient-glow {
    position: fixed;
    top: -20%;
    left: 50%;
    transform: translateX(-50%);
    width: 800px;
    height: 600px;
    background: radial-gradient(circle, rgba(16, 185, 129, 0.08) 0%, rgba(56, 189, 248, 0.05) 50%, transparent 80%);
    pointer-events: none;
    z-index: 0;
}

.page-wrapper {
    position: relative;
    z-index: 1;
    max-width: 1120px;
    margin: 0 auto;
    padding: 24px 20px 60px;
    display: flex;
    flex-direction: column;
    min-height: 100vh;
}

/* Navbar */
.navbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 14px 20px;
    background: var(--surface-glass);
    backdrop-filter: blur(16px);
    -webkit-backdrop-filter: blur(16px);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    box-shadow: var(--card-shadow);
    margin-bottom: 40px;
}

.brand {
    display: flex;
    align-items: center;
    gap: 12px;
}

.brand-icon {
    width: 38px;
    height: 38px;
    border-radius: var(--radius-md);
    background: linear-gradient(135deg, rgba(16, 185, 129, 0.2), rgba(56, 189, 248, 0.2));
    border: 1px solid var(--border);
    display: flex;
    align-items: center;
    justify-content: center;
    color: var(--primary);
}

.brand-icon svg {
    width: 20px;
    height: 20px;
}

.brand-text {
    display: flex;
    align-items: center;
    gap: 8px;
}

.brand-name {
    font-size: 18px;
    font-weight: 800;
    letter-spacing: -0.02em;
    color: var(--text-primary);
}

.brand-pill {
    font-size: 10px;
    font-weight: 700;
    text-transform: uppercase;
    letter-spacing: 0.08em;
    padding: 2px 8px;
    border-radius: var(--radius-pill);
    background: rgba(56, 189, 248, 0.15);
    color: var(--accent-cyan);
    border: 1px solid rgba(56, 189, 248, 0.3);
}

.nav-actions {
    display: flex;
    align-items: center;
    gap: 12px;
}

.status-indicator {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 14px;
    border-radius: var(--radius-pill);
    background: rgba(16, 185, 129, 0.12);
    border: 1px solid rgba(16, 185, 129, 0.25);
    color: var(--primary);
    font-size: 12px;
    font-weight: 700;
}

.pulse-dot {
    width: 8px;
    height: 8px;
    border-radius: 50%;
    background-color: var(--primary);
    box-shadow: 0 0 0 0 var(--primary-glow);
    animation: pulseGlow 2s infinite;
}

@keyframes pulseGlow {
    0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0.7); }
    70% { transform: scale(1); box-shadow: 0 0 0 8px rgba(16, 185, 129, 0); }
    100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(16, 185, 129, 0); }
}

.btn-icon {
    width: 38px;
    height: 38px;
    border-radius: var(--radius-md);
    background: var(--surface-card);
    border: 1px solid var(--border);
    color: var(--text-muted);
    display: flex;
    align-items: center;
    justify-content: center;
    cursor: pointer;
    transition: var(--transition);
}

.btn-icon:hover {
    color: var(--text-primary);
    border-color: var(--border-hover);
    transform: translateY(-1px);
}

.btn-icon svg {
    width: 18px;
    height: 18px;
}

.hidden {
    display: none !important;
}

/* Hero Section */
.hero-section {
    text-align: center;
    padding: 20px 0 48px;
    display: flex;
    flex-direction: column;
    align-items: center;
}

.eyebrow-badge {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 6px 16px;
    background: var(--surface-card);
    border: 1px solid var(--border);
    border-radius: var(--radius-pill);
    font-size: 12px;
    font-weight: 700;
    color: var(--primary);
    margin-bottom: 20px;
    letter-spacing: 0.04em;
}

.badge-dot {
    width: 6px;
    height: 6px;
    border-radius: 50%;
    background-color: var(--primary);
}

.hero-title {
    font-size: clamp(32px, 5vw, 52px);
    font-weight: 800;
    line-height: 1.15;
    letter-spacing: -0.03em;
    max-width: 820px;
    margin-bottom: 16px;
    color: var(--text-primary);
}

.hero-subtitle {
    font-size: 16px;
    color: var(--text-muted);
    max-width: 620px;
    line-height: 1.6;
    margin-bottom: 32px;
}

/* Host Info Pill */
.host-pill {
    display: flex;
    align-items: center;
    gap: 20px;
    background: var(--surface-card);
    border: 1px solid var(--border);
    padding: 12px 24px;
    border-radius: var(--radius-pill);
    box-shadow: var(--card-shadow);
    margin-bottom: 32px;
    flex-wrap: wrap;
    justify-content: center;
}

.host-item {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: 2px;
}

.host-label {
    font-size: 10px;
    font-weight: 700;
    color: var(--text-subtle);
    letter-spacing: 0.06em;
}

.host-value {
    font-size: 13px;
    font-weight: 700;
    color: var(--text-primary);
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
}

.accent-cyan { color: var(--accent-cyan); }
.accent-mint { color: var(--primary); }

.host-divider {
    width: 1px;
    height: 24px;
    background: var(--border);
}

/* Buttons */
.cta-actions {
    display: flex;
    gap: 14px;
    flex-wrap: wrap;
    justify-content: center;
}

.btn {
    display: inline-flex;
    align-items: center;
    gap: 8px;
    padding: 12px 24px;
    border-radius: var(--radius-md);
    font-size: 14px;
    font-weight: 700;
    text-decoration: none;
    cursor: pointer;
    transition: var(--transition);
    border: none;
}

.btn-svg {
    width: 16px;
    height: 16px;
}

.btn-primary {
    background: var(--primary);
    color: #090d16;
    box-shadow: 0 4px 14px var(--primary-glow);
}

.btn-primary:hover {
    transform: translateY(-2px);
    box-shadow: 0 6px 20px var(--primary-glow);
    opacity: 0.95;
}

.btn-secondary {
    background: var(--surface-card);
    color: var(--text-primary);
    border: 1px solid var(--border);
}

.btn-secondary:hover {
    background: var(--surface-card-hover);
    border-color: var(--border-hover);
    transform: translateY(-2px);
}

/* Metrics Grid */
.metrics-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(220px, 1fr));
    gap: 16px;
    margin-bottom: 56px;
}

.metric-card {
    background: var(--surface-glass);
    backdrop-filter: blur(12px);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: 20px;
    box-shadow: var(--card-shadow);
    transition: var(--transition);
}

.metric-card:hover {
    border-color: var(--border-hover);
    transform: translateY(-2px);
}

.metric-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    margin-bottom: 12px;
}

.metric-title {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.08em;
    color: var(--text-subtle);
}

.metric-icon-box {
    width: 32px;
    height: 32px;
    border-radius: var(--radius-sm);
    display: flex;
    align-items: center;
    justify-content: center;
}

.metric-icon-box svg {
    width: 16px;
    height: 16px;
}

.metric-icon-box.mint { background: rgba(16, 185, 129, 0.15); color: var(--primary); }
.metric-icon-box.cyan { background: rgba(56, 189, 248, 0.15); color: var(--accent-cyan); }
.metric-icon-box.purple { background: rgba(168, 85, 247, 0.15); color: var(--accent-purple); }
.metric-icon-box.amber { background: rgba(245, 158, 11, 0.15); color: var(--accent-amber); }

.metric-val {
    font-size: 22px;
    font-weight: 800;
    color: var(--text-primary);
    margin-bottom: 4px;
    letter-spacing: -0.02em;
}

.metric-meta {
    font-size: 12px;
    color: var(--text-muted);
}

/* Features Section */
.features-section {
    margin-bottom: 60px;
}

.section-heading {
    text-align: center;
    max-width: 620px;
    margin: 0 auto 36px;
}

.section-tag {
    font-size: 11px;
    font-weight: 800;
    letter-spacing: 0.1em;
    color: var(--accent-cyan);
    text-transform: uppercase;
    margin-bottom: 8px;
    display: block;
}

.section-title {
    font-size: 28px;
    font-weight: 800;
    color: var(--text-primary);
    letter-spacing: -0.02em;
    margin-bottom: 10px;
}

.section-desc {
    font-size: 15px;
    color: var(--text-muted);
}

.features-grid {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
    gap: 20px;
}

.feature-card {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    padding: 24px;
    box-shadow: var(--card-shadow);
    transition: var(--transition);
}

.feature-card:hover {
    border-color: var(--border-hover);
    transform: translateY(-3px);
}

.feature-icon {
    font-size: 28px;
    margin-bottom: 16px;
    display: inline-block;
}

.feature-card h3 {
    font-size: 17px;
    font-weight: 700;
    color: var(--text-primary);
    margin-bottom: 8px;
}

.feature-card p {
    font-size: 13.5px;
    color: var(--text-muted);
    line-height: 1.55;
}

/* Playground / Console */
.playground-section {
    margin-bottom: 60px;
}

.console-box {
    background: var(--surface);
    border: 1px solid var(--border);
    border-radius: var(--radius-lg);
    overflow: hidden;
    box-shadow: var(--card-shadow);
}

.console-toolbar {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: 12px 18px;
    background: var(--surface-card);
    border-bottom: 1px solid var(--border);
    flex-wrap: wrap;
    gap: 12px;
}

.console-actions {
    display: flex;
    gap: 8px;
    flex-wrap: wrap;
}

.chip {
    padding: 6px 14px;
    border-radius: var(--radius-pill);
    background: transparent;
    border: 1px solid var(--border);
    color: var(--text-muted);
    font-size: 12px;
    font-weight: 700;
    font-family: ui-monospace, SFMono-Regular, monospace;
    cursor: pointer;
    transition: var(--transition);
}

.chip:hover {
    color: var(--text-primary);
    border-color: var(--border-hover);
}

.chip.active {
    background: var(--primary);
    color: #090d16;
    border-color: var(--primary);
}

.console-meta {
    display: flex;
    align-items: center;
    gap: 12px;
}

.response-status {
    font-size: 11px;
    font-weight: 800;
    color: var(--primary);
    background: rgba(16, 185, 129, 0.12);
    padding: 3px 8px;
    border-radius: var(--radius-sm);
}

.response-timer {
    font-size: 11px;
    font-weight: 700;
    color: var(--text-subtle);
    font-family: ui-monospace, monospace;
}

.btn-copy {
    display: inline-flex;
    align-items: center;
    gap: 6px;
    background: transparent;
    border: 1px solid var(--border);
    color: var(--text-muted);
    padding: 5px 10px;
    border-radius: var(--radius-sm);
    font-size: 11px;
    font-weight: 600;
    cursor: pointer;
    transition: var(--transition);
}

.btn-copy:hover {
    color: var(--text-primary);
    border-color: var(--border-hover);
}

.copy-svg {
    width: 14px;
    height: 14px;
}

.code-view {
    padding: 20px;
    margin: 0;
    background: #04070e;
    color: #38bdf8;
    font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
    font-size: 12.5px;
    line-height: 1.6;
    overflow-x: auto;
    max-height: 280px;
}

[data-theme="light"] .code-view {
    background: #0f172a;
    color: #38bdf8;
}

/* Guide Section */
.guide-section {
    display: grid;
    grid-template-columns: repeat(auto-fit, minmax(280px, 1fr));
    gap: 16px;
    margin-bottom: 60px;
}

.guide-card {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    background: var(--surface-card);
    border: 1px solid var(--border);
    border-radius: var(--radius-md);
    padding: 20px;
}

.guide-step-num {
    width: 32px;
    height: 32px;
    border-radius: 50%;
    background: var(--primary);
    color: #090d16;
    font-weight: 800;
    font-size: 14px;
    display: flex;
    align-items: center;
    justify-content: center;
    flex-shrink: 0;
}

.guide-content h4 {
    font-size: 15px;
    font-weight: 700;
    color: var(--text-primary);
    margin-bottom: 4px;
}

.guide-content p {
    font-size: 13px;
    color: var(--text-muted);
    line-height: 1.5;
}

.guide-content code {
    background: rgba(56, 189, 248, 0.15);
    color: var(--accent-cyan);
    padding: 2px 6px;
    border-radius: 4px;
    font-size: 12px;
}

/* Footer */
.footer {
    margin-top: auto;
    padding-top: 28px;
    border-top: 1px solid var(--border);
}

.footer-inner {
    display: flex;
    align-items: center;
    justify-content: space-between;
    flex-wrap: wrap;
    gap: 16px;
}

.footer-brand {
    display: flex;
    align-items: center;
    gap: 8px;
}

.footer-title {
    font-size: 14px;
    font-weight: 800;
    color: var(--primary);
}

.footer-sub {
    font-size: 12px;
    color: var(--text-subtle);
}

.footer-security {
    display: flex;
    align-items: center;
    gap: 6px;
    font-size: 12px;
    color: var(--text-muted);
}

.lock-svg {
    width: 14px;
    height: 14px;
    color: var(--primary);
}

/* Responsive Media Queries */
@media (max-width: 768px) {
    .page-wrapper {
        padding: 16px 14px 40px;
    }

    .navbar {
        margin-bottom: 24px;
        padding: 12px 14px;
    }

    .hero-title {
        font-size: 30px;
    }

    .host-pill {
        flex-direction: column;
        align-items: center;
        gap: 10px;
        border-radius: var(--radius-md);
        padding: 14px;
        width: 100%;
    }

    .host-divider {
        width: 80%;
        height: 1px;
    }

    .host-item {
        align-items: center;
    }

    .cta-actions {
        width: 100%;
        flex-direction: column;
    }

    .btn {
        width: 100%;
        justify-content: center;
    }

    .console-toolbar {
        flex-direction: column;
        align-items: flex-start;
    }

    .console-meta {
        width: 100%;
        justify-content: space-between;
    }

    .footer-inner {
        flex-direction: column;
        align-items: flex-start;
    }
}
""".trimIndent()

    private val APP_JS = """/**
 * AndServer - Modern & Clean Layout Client Script
 */

document.addEventListener('DOMContentLoaded', () => {
    // 1. Detecção dinâmica do Host e Protocolo
    const hostEl = document.getElementById('currentHost');
    const protoEl = document.getElementById('currentProto');
    const pingEl = document.getElementById('currentPing');

    if (hostEl) hostEl.textContent = window.location.host || '127.0.0.1:8443';
    if (protoEl) protoEl.textContent = (window.location.protocol.toUpperCase().replace(':', '') || 'HTTPS') + ' / TLS 1.3';

    // 2. Alternância de Tema (Dark / Light) com persistência local
    const themeToggle = document.getElementById('themeToggle');
    const iconSun = document.getElementById('themeIconSun');
    const iconMoon = document.getElementById('themeIconMoon');

    function applyTheme(theme) {
        if (theme === 'light') {
            document.documentElement.setAttribute('data-theme', 'light');
            iconSun.classList.add('hidden');
            iconMoon.classList.remove('hidden');
        } else {
            document.documentElement.removeAttribute('data-theme');
            iconSun.classList.remove('hidden');
            iconMoon.classList.add('hidden');
        }
    }

    const savedTheme = localStorage.getItem('andserver_theme') || 
        (window.matchMedia && window.matchMedia('(prefers-color-scheme: light)').matches ? 'light' : 'dark');
    applyTheme(savedTheme);

    if (themeToggle) {
        themeToggle.addEventListener('click', () => {
            const current = document.documentElement.getAttribute('data-theme') === 'light' ? 'light' : 'dark';
            const next = current === 'light' ? 'dark' : 'light';
            localStorage.setItem('andserver_theme', next);
            applyTheme(next);
        });
    }

    // 3. Playground / Console de Teste de APIs
    const apiOutput = document.getElementById('apiOutput');
    const responseStatus = document.getElementById('responseStatus');
    const responseTimer = document.getElementById('responseTimer');
    const btnHealth = document.getElementById('btnHealth');
    const btnStatus = document.getElementById('btnStatus');
    const btnFiles = document.getElementById('btnFiles');
    const btnCopy = document.getElementById('btnCopy');
    const copyText = document.getElementById('copyText');
    const chips = [btnHealth, btnStatus, btnFiles];

    let lastResult = '';

    async function callEndpoint(url, activeBtn) {
        chips.forEach(c => c && c.classList.remove('active'));
        if (activeBtn) activeBtn.classList.add('active');

        if (apiOutput) apiOutput.innerHTML = `<code>Consultando ${"$"}{url}...</code>`;
        if (responseStatus) {
            responseStatus.textContent = 'Enviando...';
            responseStatus.style.color = 'var(--text-subtle)';
        }

        const start = performance.now();
        try {
            const res = await fetch(url);
            const duration = Math.round(performance.now() - start);

            if (responseTimer) responseTimer.textContent = `${"$"}{duration} ms`;
            if (pingEl) pingEl.textContent = `${"$"}{duration} ms`;

            if (responseStatus) {
                responseStatus.textContent = `${"$"}{res.status} ${"$"}{res.statusText || 'OK'}`;
                responseStatus.style.color = res.ok ? 'var(--primary)' : 'var(--accent-amber)';
            }

            const contentType = res.headers.get('content-type') || '';
            let text = '';
            if (contentType.includes('application/json')) {
                const json = await res.json();
                text = JSON.stringify(json, null, 2);
            } else {
                text = await res.text();
            }

            lastResult = text;
            if (apiOutput) {
                apiOutput.innerHTML = `<code>${"$"}{escapeHtml(text)}</code>`;
            }
        } catch (err) {
            const duration = Math.round(performance.now() - start);
            if (responseTimer) responseTimer.textContent = `${"$"}{duration} ms`;
            if (responseStatus) {
                responseStatus.textContent = 'ERRO';
                responseStatus.style.color = '#ef4444';
            }
            lastResult = err.message;
            if (apiOutput) {
                apiOutput.innerHTML = `<code>Falha na requisição: ${"$"}{escapeHtml(err.message)}\n\nCertifique-se de que o servidor AndServer está em execução.</code>`;
            }
        }
    }

    if (btnHealth) btnHealth.addEventListener('click', () => callEndpoint('/health', btnHealth));
    if (btnStatus) btnStatus.addEventListener('click', () => callEndpoint('/api/status', btnStatus));
    if (btnFiles) btnFiles.addEventListener('click', () => callEndpoint('/api/files', btnFiles));

    // Botão de Copiar JSON / Texto
    if (btnCopy) {
        btnCopy.addEventListener('click', () => {
            if (!lastResult) return;
            navigator.clipboard.writeText(lastResult).then(() => {
                if (copyText) copyText.textContent = 'Copiado!';
                setTimeout(() => {
                    if (copyText) copyText.textContent = 'Copiar';
                }, 2000);
            }).catch(() => {
                if (copyText) copyText.textContent = 'Falha ao copiar';
            });
        });
    }

    function escapeHtml(str) {
        return str
            .replace(/&/g, '&amp;')
            .replace(/</g, '&lt;')
            .replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;')
            .replace(/'/g, '&#039;');
    }

    // Inicialização silenciosa de verificação de integridade
    setTimeout(() => {
        callEndpoint('/health', btnHealth);
    }, 400);
});
""".trimIndent()
}