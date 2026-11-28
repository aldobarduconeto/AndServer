package com.andserver

import java.io.File

/**
 * Renderizador isolado das interfaces HTML da aplicação Web do AndServer:
 * - Painel de Controle Web Completo (/ e /index.html)
 * - Explorador de Diretórios HTML (/public/...)
 */
object WebDashboardRenderer {

    fun renderDirectoryPage(dir: File, root: File, allowed: (String) -> Boolean): String {
        val rel = dir.relativeTo(root).invariantSeparatorsPath
        val items = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() }).orEmpty()
            .filter { it.isDirectory || allowed(it.name) }.joinToString("") { file ->
                val child = if (rel.isEmpty()) file.name else "$rel/${file.name}"
                val suffix = if (file.isDirectory) "/" else ""
                val icon = MimeTypes.getFileIcon(file.name, file.isDirectory)
                val sizeText = if (file.isFile) FormatUtils.formatBytes(file.length()) else "Pasta"
                """<div class="file-row"><a class="file-link" href="/public/${FormatUtils.urlPath(child)}$suffix"><span class="icon">$icon</span><span class="name">${FormatUtils.escapeHtml(file.name)}$suffix</span></a><span class="file-size">$sizeText</span></div>"""
            }
        val parent = if (rel.contains('/')) rel.substringBeforeLast('/') else ""
        val up = if (rel.isNotEmpty()) """<a class="btn-back" href="/public/${FormatUtils.urlPath(parent)}">← Pasta anterior</a>""" else ""
        return """<!doctype html><html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0b0f17"><title>Arquivos · AndServer</title><link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b0f17'/%3E%3Cpath d='M8 10h16v4H8zm0 8h16v4H8z' fill='%231a253c' stroke='%23263450' stroke-width='.5'/%3E%3Ccircle cx='21' cy='12' r='1' fill='%2310b981'/%3E%3Ccircle cx='21' cy='20' r='1' fill='%2338bdf8'/%3E%3Cpath d='M11 6a8 8 0 0 1 10 0' stroke='%2338bdf8' stroke-width='1.5' stroke-linecap='round' fill='none'/%3E%3C/svg%3E"><style>
            :root{--bg:#0b0f17;--card:#131b2c;--border:#263450;--primary:#10b981;--text:#f8fafc;--text-muted:#94a3b8}
            *{box-sizing:border-box;margin:0;padding:0}body{font:15px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,sans-serif;background:var(--bg);color:var(--text);padding:32px 18px 60px;max-width:840px;margin:0 auto}
            header{margin-bottom:24px}h1{font-size:24px;font-weight:700;margin-bottom:8px}.path-sub{color:var(--text-muted);font-size:13px;word-break:break-all}
            .card{background:var(--card);border:1px solid var(--border);border-radius:16px;padding:8px 16px;box-shadow:0 4px 20px -2px rgba(0,0,0,0.4)}
            .file-row{display:flex;align-items:center;justify-content:space-between;padding:12px 6px;border-bottom:1px solid rgba(255,255,255,0.06);gap:12px}
            .file-row:last-child{border-bottom:none}.file-link{display:flex;align-items:center;gap:10px;color:var(--text);text-decoration:none;font-weight:500;min-width:0;overflow:hidden}
            .file-link:hover .name{color:var(--primary)}.file-link .icon{font-size:18px}.file-link .name{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
            .file-size{color:var(--text-muted);font-size:13px;flex-shrink:0}.btn-back{display:inline-flex;align-items:center;gap:6px;color:var(--primary);text-decoration:none;font-weight:600;font-size:13px;margin-bottom:16px}
            @media(max-width:600px){body{padding:20px 14px}h1{font-size:20px}}
        </style></head><body><header>$up<h1>Explorador de Arquivos</h1><div class="path-sub">/${FormatUtils.escapeHtml(rel)}</div></header><main class="card">${if (items.isEmpty()) """<div style="padding:24px;text-align:center;color:var(--text-muted)">Nenhum arquivo nesta pasta.</div>""" else items}</main></body></html>"""
    }

    fun renderWebDashboard(
        type: String,
        filters: Set<String>,
        passwordEnabled: Boolean,
        networkScope: String,
        port: Int,
        workers: Int,
        publicIp: String?,
        webSocketToken: String
    ): String {
        val typeLabel = ServerProfiles.nameOf(type)
        val filterLabels = if ("all" in filters) "Todos os formatos" else filters.mapNotNull {
            when (it) { "images" -> "Imagens"; "documents" -> "Documentos"; "audio" -> "Áudio"; "video" -> "Vídeo"; "archives" -> "Compactados"; else -> null }
        }.joinToString(" · ").ifEmpty { "Nenhum formato permitido" }
        val protection = if (!passwordEnabled) "Desativada (Acesso livre)" else "Ativa · HTTP Basic (Com Escudo Anti-Força Bruta)"
        val scopeLabel = if (networkScope == "internet") "Toda a Internet (Público)" else "Rede Local (LAN)"

        return """<!doctype html><html lang="pt-br"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="theme-color" content="#0b0f17"><title>AndServer Â· Painel do Servidor</title><link rel="icon" type="image/svg+xml" href="data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 32 32'%3E%3Crect width='32' height='32' rx='8' fill='%230b0f17'/%3E%3Cpath d='M8 10h16v4H8zm0 8h16v4H8z' fill='%231a253c' stroke='%23263450' stroke-width='.5'/%3E%3Ccircle cx='21' cy='12' r='1' fill='%2310b981'/%3E%3Ccircle cx='21' cy='20' r='1' fill='%2338bdf8'/%3E%3Cpath d='M11 6a8 8 0 0 1 10 0' stroke='%2338bdf8' stroke-width='1.5' stroke-linecap='round' fill='none'/%3E%3C/svg%3E"><style>
            :root{
                --bg:#0b0f17;--card:#131b2c;--card-alt:#1a253c;--border:#263450;--border-subtle:rgba(255,255,255,0.07);
                --primary:#10b981;--primary-glow:rgba(16,185,129,0.25);--text:#f8fafc;--text-muted:#94a3b8;--text-subtle:#64748b;
                --danger:#ef4444;--danger-bg:#3d1a24;--radius:16px;--radius-sm:10px
            }
            *{box-sizing:border-box;margin:0;padding:0}
            body{font:15px -apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;background:var(--bg);color:var(--text);padding:24px 18px 60px;max-width:1120px;margin:0 auto;-webkit-font-smoothing:antialiased}
            header{display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:16px;margin-bottom:28px;padding-bottom:18px;border-bottom:1px solid var(--border)}
            .brand-wrapper{display:flex;flex-direction:column;gap:4px}
            .brand-badge{display:inline-flex;align-items:center;gap:6px;background:var(--card-alt);border:1px solid var(--border);color:var(--primary);font-size:11px;font-weight:800;letter-spacing:.08em;padding:4px 10px;border-radius:999px;width:fit-content}
            .brand-dot{width:6px;height:6px;border-radius:50%;background:var(--primary)}
            h1{font-size:26px;font-weight:800;color:var(--text);letter-spacing:-.02em}
            .subtitle{color:var(--text-muted);font-size:13px}
            .header-badges{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
            .live-badge{display:inline-flex;align-items:center;gap:8px;background:rgba(16,185,129,0.12);border:1px solid rgba(16,185,129,0.3);color:var(--primary);font-size:12px;font-weight:700;padding:7px 14px;border-radius:999px}
            .scope-badge{display:inline-flex;align-items:center;gap:6px;background:rgba(56,189,248,0.12);border:1px solid rgba(56,189,248,0.3);color:#38bdf8;font-size:12px;font-weight:700;padding:7px 14px;border-radius:999px}
            .pulse-dot{width:8px;height:8px;border-radius:50%;background:var(--primary);box-shadow:0 0 0 0 rgba(16,185,129,0.7);animation:pulse 2s infinite}
            @keyframes pulse{0%{transform:scale(.95);box-shadow:0 0 0 0 rgba(16,185,129,.7)}70%{transform:scale(1);box-shadow:0 0 0 8px rgba(16,185,129,0)}100%{transform:scale(.95);box-shadow:0 0 0 0 rgba(16,185,129,0)}}
            h2{font-size:16px;font-weight:700;color:var(--text);margin-bottom:14px;display:flex;align-items:center;gap:8px}
            .grid-stats{display:grid;grid-template-columns:repeat(auto-fit,minmax(160px,1fr));gap:12px;margin-bottom:16px}
            .card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:18px;margin-bottom:16px;box-shadow:0 4px 20px -2px rgba(0,0,0,0.35)}
            .stat-card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px 16px}
            .label{font-size:11px;color:var(--text-subtle);text-transform:uppercase;font-weight:700;letter-spacing:.06em}
            .value{font-size:18px;font-weight:700;color:var(--text);margin-top:6px;overflow-wrap:anywhere}
            .nav-tabs{display:flex;gap:8px;margin:24px 0 16px;background:var(--card);padding:5px;border-radius:12px;border:1px solid var(--border);width:fit-content;flex-wrap:wrap}
            .tab-btn{border:0;background:transparent;color:var(--text-muted);font-weight:600;font-size:13px;padding:9px 18px;border-radius:8px;cursor:pointer;transition:all .2s ease}
            .tab-btn.selected{background:var(--primary);color:#0b0f17;box-shadow:0 2px 8px var(--primary-glow)}
            .toolbar{display:flex;gap:10px;flex-wrap:wrap;align-items:center;margin-bottom:16px}
            input,select{color:var(--text);background:var(--card-alt);border:1px solid var(--border);border-radius:8px;padding:10px 14px;font-size:14px;transition:border-color .2s;outline:none}
            input:focus,select:focus{border-color:var(--primary)}
            input[type=search]{flex:1;min-width:200px}
            .btn{border:0;border-radius:8px;padding:10px 16px;font-weight:600;font-size:13px;cursor:pointer;display:inline-flex;align-items:center;gap:6px;transition:all .15s ease;text-decoration:none}
            .btn-primary{background:var(--primary);color:#0b0f17}.btn-primary:hover{opacity:.9}
            .btn-secondary{background:var(--card-alt);color:var(--text);border:1px solid var(--border)}.btn-secondary:hover{background:#23304c}
            .btn-danger{background:var(--danger-bg);color:#fca5a5;border:1px solid rgba(239,68,68,0.25);padding:6px 10px;font-size:12px}.btn-danger:hover{background:#52222d}
            .btn-sm{padding:6px 10px;font-size:12px;border-radius:6px}
            .dropzone{border:2px dashed var(--border);border-radius:var(--radius-sm);padding:24px 16px;text-align:center;cursor:pointer;background:rgba(26,37,60,0.3);transition:all .2s;position:relative;margin-bottom:16px}
            .dropzone.dragover{border-color:var(--primary);background:rgba(16,185,129,0.06)}
            .dropzone input[type=file]{position:absolute;inset:0;opacity:0;cursor:pointer;width:100%;height:100%}
            .dropzone .icon{font-size:28px;margin-bottom:6px}.dropzone .hint{font-size:12px;color:var(--text-subtle);margin-top:4px}
            .file-item{display:flex;align-items:center;justify-content:space-between;padding:12px 8px;border-bottom:1px solid var(--border-subtle);gap:12px}
            .file-item:last-child{border-bottom:none}.file-meta{display:flex;align-items:center;gap:10px;min-width:0;overflow:hidden}
            .file-meta a.clickable-title{color:var(--text);text-decoration:none;font-weight:500;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;cursor:pointer}
            .file-meta a.clickable-title:hover{color:var(--primary);text-decoration:underline}
            .file-meta .icon{font-size:18px;flex-shrink:0}
            .file-actions{display:flex;align-items:center;gap:8px;flex-shrink:0}
            .file-size{font-size:13px;color:var(--text-muted);margin-right:6px}
            .log-box{background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px;max-height:220px;overflow-y:auto;font-family:monospace;font-size:12px;color:var(--text-muted)}
            .log-box li{list-style:none;padding:4px 0;border-bottom:1px solid rgba(255,255,255,0.04);word-break:break-all}
            .fields-grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(220px,1fr));gap:14px;margin-bottom:14px}
            .field{display:grid;gap:6px}.field label{color:var(--text-muted);font-size:13px;font-weight:500}
            .checks{display:flex;gap:14px;flex-wrap:wrap;padding:6px 0}.checks label{display:flex;align-items:center;gap:6px;font-size:13px;color:var(--text);cursor:pointer}
            .notice{background:rgba(245,158,11,0.08);border:1px solid rgba(245,158,11,0.25);color:#fde68a;font-size:13px;line-height:1.5;padding:12px 14px;border-radius:var(--radius-sm);margin-top:14px}
            .api-endpoint{display:flex;align-items:center;justify-content:space-between;padding:11px 0;border-bottom:1px solid var(--border-subtle);font-size:13px}
            .api-endpoint:last-child{border-bottom:none}.api-endpoint code{color:var(--primary);background:var(--card-alt);padding:3px 7px;border-radius:5px;font-family:monospace;font-size:12px}
            .hidden{display:none}
            .modal-backdrop{position:fixed;inset:0;background:rgba(0,0,0,0.75);backdrop-filter:blur(6px);display:flex;align-items:center;justify-content:center;z-index:9999;padding:16px}
            .modal-box{background:var(--card);border:1px solid var(--border);border-radius:18px;max-width:540px;width:100%;padding:22px;box-shadow:0 10px 40px rgba(0,0,0,0.8);position:relative}
            .modal-title{font-size:18px;font-weight:700;margin-bottom:12px}
            .modal-close{position:absolute;top:16px;right:16px;background:none;border:none;color:var(--text-muted);font-size:20px;cursor:pointer}
            .qr-display{display:flex;flex-direction:column;align-items:center;justify-content:center;padding:14px 0}
            .qr-display img{width:240px;height:240px;border-radius:12px;background:#fff;padding:8px}
            .media-box{max-height:75vh;display:flex;align-items:center;justify-content:center;margin:12px 0}
            .media-box img{max-width:100%;max-height:70vh;border-radius:8px;object-fit:contain}
            .media-box video,.media-box audio{width:100%;border-radius:8px}
            @media(max-width:640px){
                body{padding:16px 12px 40px}header{flex-direction:column;align-items:flex-start}
                .grid-stats{grid-template-columns:repeat(2,1fr);gap:8px}.nav-tabs{width:100%}
                .tab-btn{flex:1;text-align:center}.toolbar{flex-direction:column;align-items:stretch}
                .toolbar input[type=search],.btn{width:100%;justify-content:center}
                .file-item{flex-direction:column;align-items:flex-start;gap:8px}
                .file-actions{width:100%;justify-content:space-between;flex-wrap:wrap}
            }
            @media(min-width:641px) and (max-width:1024px){
                .grid-stats{grid-template-columns:repeat(3,1fr)}
            }
            @media(min-width:1025px){
                .grid-stats{grid-template-columns:repeat(4,1fr)}
            }
        </style></head><body>
            <header>
                <div class="brand-wrapper">
                    <div class="brand-badge"><span class="brand-dot"></span>AND SERVER</div>
                    <h1>Central do Servidor</h1>
                    <div class="subtitle">ServiÃ§o HTTPS seguro com controle de acesso LAN / Internet</div>
                </div>
                <div class="header-badges">
                    <button class="btn btn-secondary btn-sm" onclick="showQrModal()">ðŸ“± QR Code de Acesso</button>
                    <div class="scope-badge">${if (networkScope == "internet") "ðŸŒ Toda a Internet" else "ðŸ  Rede Local"}</div>
                    <div class="live-badge"><span class="pulse-dot"></span>ONLINE</div>
                </div>
            </header>

            <main>
                <section class="card">
                    <h2>VisÃ£o Geral do Servidor</h2>
                    <div class="grid-stats">
                        <div class="stat-card"><div class="label">Escopo de Rede</div><div class="value">$scopeLabel</div></div>
                        <div class="stat-card"><div class="label">Porta HTTPS</div><div class="value">$port</div></div>
                        <div class="stat-card"><div class="label">IP Local (LAN)</div><div id="statLocalIp" class="value">â€”</div></div>
                        <div class="stat-card"><div class="label">IP PÃºblico (Internet)</div><div id="statPublicIp" class="value">${publicIp ?: "â€”"}</div></div>
                        <div class="stat-card"><div class="label">AutenticaÃ§Ã£o</div><div class="value">$protection</div></div>
                        <div class="stat-card"><div class="label">Escudo ForÃ§a Bruta</div><div class="value">ðŸ›¡ï¸ Ativo (Auto-Ban)</div></div>
                        <div class="stat-card"><div class="label">ConexÃµes Concorrentes</div><div class="value">$workers</div></div>
                        <div class="stat-card"><div class="label">Limite de Upload</div><div class="value">100 MB</div></div>
                        <div class="stat-card"><div class="label">RequisiÃ§Ãµes</div><div id="requests" class="value">â€”</div></div>
                        <div class="stat-card"><div class="label">Clientes Conectados</div><div id="clients" class="value">â€”</div></div>
                        <div class="stat-card"><div class="label">Dados Recebidos</div><div id="incoming" class="value">â€”</div></div>
                        <div class="stat-card"><div class="label">Dados Enviados</div><div id="outgoing" class="value">â€”</div></div>
                    </div>
                    <div class="notice">
                        ${if (networkScope == "internet") "âš ï¸ <strong>Modo Toda a Internet Ativo:</strong> O servidor aceita conexÃµes externas de qualquer rede. O escudo de forÃ§a bruta bloqueia tentativas abusivas apÃ³s 5 erros." else "ðŸ  <strong>Modo Rede Local Ativo:</strong> ConexÃµes externas de fora da sua rede Wi-Fi sÃ£o bloqueadas automaticamente pelo firewall interno."}
                    </div>
                </section>

                <nav class="nav-tabs">
                    <button class="tab-btn selected" data-tab="files">ðŸ“ Arquivos &amp; Drive</button>
                    <button class="tab-btn" data-tab="streaming">ðŸŽ¬ Streaming de MÃ­dia</button>
                    <button class="tab-btn" data-tab="drive">ðŸ’¾ Mapear Drive (WebDAV)</button>
                    <button class="tab-btn" data-tab="config">âš™ï¸ ConfiguraÃ§Ãµes &amp; Rede</button>
                    <button class="tab-btn" data-tab="live">âš¡ Eventos ao Vivo</button>
                    <button class="tab-btn" data-tab="api">ðŸ“¡ API, ZIP &amp; TLS</button>
                </nav>

                <!-- ABA 1: ARQUIVOS & DRIVE -->
                <section id="files">
                    <div class="card">
                        <h2>Gerenciador de Arquivos &amp; Drive</h2>
                        <div id="dropzone" class="dropzone">
                            <input id="picker" type="file" multiple onchange="uploadFiles()">
                            <div class="icon">â˜ï¸</div>
                            <div style="font-weight:600">Arraste e solte arquivos aqui para enviar</div>
                            <div class="hint">ou clique para selecionar do seu dispositivo (atÃ© 500 MB por arquivo)</div>
                        </div>
                        <div class="toolbar">
                            <input id="search" type="search" placeholder="ðŸ” Filtrar arquivos..." oninput="filterRows()">
                            <button class="btn btn-secondary" onclick="createFolder()">âž• Nova Pasta</button>
                            <a class="btn btn-secondary" href="/api/zip" download="arquivos-completos.zip">ðŸ“¦ Baixar Tudo (.ZIP)</a>
                            <button class="btn btn-secondary" onclick="refresh()">ðŸ”„ Atualizar</button>
                        </div>
                        <div id="message" style="margin-bottom:10px;font-size:13px;color:var(--primary);font-weight:500"></div>
                        <div id="filelist">Carregando lista de arquivos...</div>
                    </div>
                </section>

                <!-- ABA: STREAMING DE MÃDIA -->
                <section id="streaming" class="hidden">
                    <div class="card">
                        <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;margin-bottom:16px">
                            <h2>ðŸŽ¬ Central de Streaming de VÃ­deo &amp; Ãudio</h2>
                            <div style="display:flex;gap:8px;flex-wrap:wrap">
                                <a class="btn btn-primary btn-sm" href="/stream/playlist.m3u" download="andserver-streaming.m3u">ðŸ“º Abrir no VLC / Smart TV (.m3u)</a>
                                <button class="btn btn-secondary btn-sm" onclick="copyM3uUrl()">ðŸ“‹ Copiar Link M3U</button>
                                <button class="btn btn-secondary btn-sm" onclick="loadMediaCatalog()">ðŸ”„ Atualizar MÃ­dias</button>
                            </div>
                        </div>

                        <!-- Player Ativo -->
                        <div id="streamingPlayerBox" style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:16px;margin-bottom:20px">
                            <div style="display:flex;justify-content:space-between;align-items:center;margin-bottom:10px">
                                <span id="playerNowPlaying" style="font-weight:700;font-size:14px;color:var(--primary)">Selecione uma mÃ­dia abaixo para reproduzir</span>
                                <span id="playerFormatBadge" class="brand-badge" style="display:none"></span>
                            </div>
                            <div id="playerView" style="min-height:120px;display:flex;align-items:center;justify-content:center">
                                <div style="color:var(--text-muted);font-size:13px;text-align:center;padding:24px">
                                    Nenhum vÃ­deo ou Ã¡udio em reproduÃ§Ã£o.<br>
                                    <span style="font-size:12px;color:var(--text-subtle)">Clique em "â–¶ Reproduzir" em qualquer item da lista abaixo para streaming instantÃ¢neo.</span>
                                </div>
                            </div>
                        </div>

                        <!-- Listas de MÃ­dia -->
                        <div class="fields-grid" style="grid-template-columns:1fr 1fr;gap:16px">
                            <div>
                                <h3 style="font-size:14px;margin-bottom:10px;color:var(--accent-cyan);display:flex;align-items:center;gap:6px">
                                    ðŸŽ¥ VÃ­deos DisponÃ­veis (<span id="countVideos">0</span>)
                                </h3>
                                <div id="videoList" style="max-height:360px;overflow-y:auto;display:flex;flex-direction:column;gap:6px">
                                    <div style="color:var(--text-muted);font-size:12px">Buscando vÃ­deos...</div>
                                </div>
                            </div>
                            <div>
                                <h3 style="font-size:14px;margin-bottom:10px;color:var(--primary);display:flex;align-items:center;gap:6px">
                                    ðŸŽµ MÃºsicas &amp; Ãudios (<span id="countAudios">0</span>)
                                </h3>
                                <div id="audioList" style="max-height:360px;overflow-y:auto;display:flex;flex-direction:column;gap:6px">
                                    <div style="color:var(--text-muted);font-size:12px">Buscando Ã¡udios...</div>
                                </div>
                            </div>
                        </div>
                    </div>
                </section>

                <!-- ABA: MAPEAR DRIVE (WEBDAV) -->
                <section id="drive" class="hidden">
                    <div class="card">
                        <div style="display:flex;justify-content:space-between;align-items:center;flex-wrap:wrap;gap:12px;margin-bottom:14px">
                            <h2>ðŸ’¾ Drive na Rede Local (Armazenamento WebDAV)</h2>
                            <div class="live-badge">âœ¨ Disco Remoto ConectÃ¡vel</div>
                        </div>
                        <p style="color:var(--text-muted);font-size:13.5px;margin-bottom:18px;line-height:1.5">
                            Transforme seu smartphone/tablet em uma <strong>unidade de disco na rede local</strong>. VocÃª pode mapeÃ¡-lo diretamente no Windows Explorer (como unidade Z:\), no Finder do macOS ou em aplicativos de arquivos no Android e iOS, salvando e abrindo arquivos como se estivessem no seu prÃ³prio computador.
                        </p>

                        <!-- Card de URL WebDAV para cÃ³pia rÃ¡pida -->
                        <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px 18px;margin-bottom:20px;display:flex;align-items:center;justify-content:space-between;flex-wrap:wrap;gap:12px">
                            <div>
                                <span class="label" style="display:block;margin-bottom:4px">ENDEREÃ‡O WEBDAV DO DISCO DE REDE</span>
                                <span id="webdavUrlDisplay" style="font-family:monospace;font-size:15px;color:var(--primary);font-weight:700"></span>
                            </div>
                            <button class="btn btn-primary" onclick="copyWebdavUrl()">ðŸ“‹ Copiar URL do Drive</button>
                        </div>

                        <!-- Medidor de Armazenamento -->
                        <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:16px;margin-bottom:22px">
                            <div style="display:flex;justify-content:space-between;margin-bottom:8px">
                                <span style="font-size:12px;font-weight:700;color:var(--text)">Capacidade de Armazenamento do Dispositivo</span>
                                <span id="storageSummary" style="font-size:12px;color:var(--text-muted)">Calculando...</span>
                            </div>
                            <div style="width:100%;height:10px;background:rgba(255,255,255,0.08);border-radius:99px;overflow:hidden">
                                <div id="storageBar" style="height:100%;width:0%;background:linear-gradient(90deg,var(--primary),var(--accent-cyan));border-radius:99px;transition:width .5s"></div>
                            </div>
                            <div style="display:flex;justify-content:space-between;margin-top:8px;font-size:11px;color:var(--text-subtle)">
                                <span id="storageUsedDetail">Ocupado: --</span>
                                <span id="storageFreeDetail">Livre: --</span>
                            </div>
                        </div>

                        <!-- InstruÃ§Ãµes passo a passo -->
                        <h3 style="font-size:15px;margin-bottom:12px;color:var(--text)">Como Mapear nos Seus Dispositivos:</h3>
                        <div class="fields-grid" style="grid-template-columns:repeat(auto-fit,minmax(240px,1fr));gap:14px">
                            <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px">
                                <div style="font-size:16px;font-weight:700;margin-bottom:6px">ðŸªŸ Windows (10 / 11)</div>
                                <ol style="font-size:12px;color:var(--text-muted);padding-left:16px;line-height:1.6">
                                    <li>Abra o <strong>Explorador de Arquivos</strong> (Win + E).</li>
                                    <li>Clique em <strong>Este Computador</strong>.</li>
                                    <li>No menu superior, clique em <strong>Mapear unidade de rede</strong>.</li>
                                    <li>Escolha uma letra (ex: <code>Z:</code>) e cole a URL do Drive copiada acima.</li>
                                    <li>Se configurou senha, marque "Conectar usando credenciais diferentes".</li>
                                </ol>
                            </div>
                            <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px">
                                <div style="font-size:16px;font-weight:700;margin-bottom:6px">ðŸ macOS (Finder)</div>
                                <ol style="font-size:12px;color:var(--text-muted);padding-left:16px;line-height:1.6">
                                    <li>No Finder, pressione <strong>Cmd + K</strong> (ou Ir &gt; Conectar ao Servidor).</li>
                                    <li>Cole o endereÃ§o WebDAV e clique em <strong>Conectar</strong>.</li>
                                    <li>O celular montarÃ¡ como um volume de disco na barra lateral.</li>
                                </ol>
                            </div>
                            <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px">
                                <div style="font-size:16px;font-weight:700;margin-bottom:6px">ðŸ§ Linux</div>
                                <ol style="font-size:12px;color:var(--text-muted);padding-left:16px;line-height:1.6">
                                    <li>No gerenciador de arquivos (Nautilus/Dolphin), vÃ¡ em <strong>Outros Locais</strong>.</li>
                                    <li>Em Conectar ao Servidor, digite: <code>davs://&lt;ip&gt;:&lt;porta&gt;/webdav</code>.</li>
                                    <li>Acesse seus arquivos diretamente pelas pastas do sistema.</li>
                                </ol>
                            </div>
                            <div style="background:var(--card-alt);border:1px solid var(--border);border-radius:var(--radius-sm);padding:14px">
                                <div style="font-size:16px;font-weight:700;margin-bottom:6px">ðŸ“± Celular &amp; Smart TV</div>
                                <ol style="font-size:12px;color:var(--text-muted);padding-left:16px;line-height:1.6">
                                    <li>Use aplicativos como <strong>CX File Explorer</strong> ou <strong>Solid Explorer</strong>.</li>
                                    <li>Selecione "Nova ConexÃ£o" &gt; <strong>WebDAV</strong>.</li>
                                    <li>Transfira fotos, vÃ­deos e documentos sem cabos e sem limites.</li>
                                </ol>
                            </div>
                        </div>
                    </div>
                </section>

                <!-- ABA 2: CONFIGURAÃ‡Ã•ES & REDE -->
                <section id="config" class="hidden">
                    <form id="settings" class="card">
                        <h2>Ajustar ConfiguraÃ§Ã£o e Acesso</h2>
                        <div class="fields-grid">
                            <div class="field">
                                <label for="cfgNetworkScope">Escopo de ConexÃ£o</label>
                                <select id="cfgNetworkScope">
                                    <option value="local">ðŸ  Apenas Rede Local (LAN) - Mesma Wi-Fi</option>
                                    <option value="internet">ðŸŒ Toda a Internet (PÃºblico / WAN) - Acesso Global</option>
                                </select>
                            </div>
                            <div class="field">
                                <label for="cfgType">Perfil de Servidor</label>
                                <select id="cfgType">
                                    <option value="drive">ðŸ’¾ Drive na Rede Local (Armazenamento WebDAV)</option>
                                    <option value="streaming">ðŸŽ¬ Servidor de Streaming de MÃ­dia</option>
                                    <option value="site">ðŸŒ Site Web Hospedado (Pasta /www)</option>
                                    <option value="web">Central Web Completa &amp; Arquivos</option>
                                    <option value="files">Servidor de Arquivos</option>
                                    <option value="api">Servidor de API JSON</option>
                                </select>
                            </div>
                            <div class="field">
                                <label for="cfgPort">Porta HTTPS (1024 a 65535)</label>
                                <input id="cfgPort" type="number" min="1024" max="65535" value="$port" required>
                            </div>
                            <div class="field">
                                <label for="cfgWorkers">ConexÃµes SimultÃ¢neas</label>
                                <select id="cfgWorkers"><option>2</option><option>4</option><option>8</option><option>12</option><option>16</option></select>
                            </div>
                        </div>

                        <div class="field" style="margin-bottom:14px">
                            <label>Formatos de Arquivo Permitidos</label>
                            <div class="checks">
                                <label><input type="checkbox" name="filter" value="all"> Todos</label>
                                <label><input type="checkbox" name="filter" value="images"> Imagens</label>
                                <label><input type="checkbox" name="filter" value="documents"> Documentos</label>
                                <label><input type="checkbox" name="filter" value="audio"> Ãudio</label>
                                <label><input type="checkbox" name="filter" value="video"> VÃ­deo</label>
                                <label><input type="checkbox" name="filter" value="archives"> Compactados</label>
                            </div>
                        </div>

                        <div class="fields-grid">
                            <div class="field">
                                <label for="cfgPassword">Senha de Acesso (HTTP Basic)</label>
                                <input id="cfgPassword" type="password" autocomplete="new-password" placeholder="MÃ­nimo 8 caracteres (ou em branco)">
                            </div>
                        </div>
                        <div style="margin-bottom:16px">
                            <label style="display:inline-flex;align-items:center;gap:6px;font-size:13px;cursor:pointer">
                                <input id="removePassword" type="checkbox"> Remover proteÃ§Ã£o por senha
                            </label>
                        </div>
                        <div class="toolbar">
                            <button class="btn btn-primary" type="submit">Salvar e Reiniciar Servidor</button>
                            <span id="configMessage" style="color:var(--text-muted);font-size:13px"></span>
                        </div>
                    </form>
                </section>

                <!-- ABA 3: EVENTOS AO VIVO -->
                <section id="live" class="hidden">
                    <div class="card">
                        <h2>Registro de Atividade e ConexÃµes em Tempo Real</h2>
                        <div id="wsStatus" style="font-size:13px;color:var(--primary);margin-bottom:10px">Conectando ao WebSocket seguroâ€¦</div>
                        <ul id="liveEvents" class="log-box"></ul>
                    </div>
                </section>

                <!-- ABA 4: API, ZIP & TLS -->
                <section id="api" class="hidden">
                    <div class="card">
                        <h2>Recursos AvanÃ§ados e Endpoints da API</h2>
                        <div class="api-endpoint"><code>GET /</code><span style="color:var(--text-muted)">Carrega o Site Web (no perfil 'site') ou Painel Admin</span></div>
                        <div class="api-endpoint"><code>GET /health</code><span style="color:var(--text-muted)">Health check do servidor</span></div>
                        <div class="api-endpoint"><code>GET /api/status</code><span style="color:var(--text-muted)">MÃ©tricas, IP pÃºblico e conexÃµes</span></div>
                        <div class="api-endpoint"><code>GET /api/update</code><span style="color:var(--text-muted)">Verificar atualizaÃ§Ãµes oficiais do GitHub em tempo real</span></div>
                        <div class="api-endpoint"><code>GET /api/zip?path={pasta}</code><span style="color:var(--text-muted)">Compactar e baixar pasta completa em ZIP</span></div>
                        <div class="api-endpoint"><code>POST /api/share</code><span style="color:var(--text-muted)">Criar link temporÃ¡rio expirÃ¡vel (1h, 24h, 7d)</span></div>
                        <div class="api-endpoint"><code>GET /share/{token}</code><span style="color:var(--text-muted)">Download por token temporÃ¡rio sem expor senha</span></div>
                        <div class="api-endpoint"><code>GET /api/qrcode?text={url}</code><span style="color:var(--text-muted)">Gerar imagem SVG do QR Code dinÃ¢mico</span></div>
                        <div class="api-endpoint"><code>GET /api/certificate</code><span style="color:var(--text-muted)">Baixar certificado pÃºblico TLS (.pem)</span></div>
                        <div class="api-endpoint"><code>WSS /ws</code><span style="color:var(--text-muted)">Eventos e telemetria em tempo real</span></div>
                        <div style="margin-top:20px;display:flex;gap:10px;flex-wrap:wrap">
                            <a class="btn btn-secondary" href="/api/certificate" download="andserver-certificate.pem">ðŸ“¥ Baixar Certificado TLS (.pem)</a>
                            <a class="btn btn-secondary" href="/api/zip" download="todos-arquivos.zip">ðŸ“¦ Baixar Todos os Arquivos (.zip)</a>
                        </div>
                    </div>
                </section>
            </main>

            <!-- MODAL: QR CODE -->
            <div id="modalQr" class="modal-backdrop hidden">
                <div class="modal-box">
                    <button class="modal-close" onclick="closeModals()">âœ•</button>
                    <div class="modal-title">ðŸ“± Conectar via QR Code</div>
                    <p style="color:var(--text-muted);font-size:13px">Aponte a cÃ¢mera do celular ou tablet para acessar instantaneamente:</p>
                    <div class="qr-display">
                        <img id="qrImage" src="" alt="QR Code">
                        <div id="qrUrlLabel" style="margin-top:12px;font-family:monospace;font-size:13px;word-break:break-all;text-align:center"></div>
                    </div>
                    <div style="display:flex;justify-content:center;gap:10px">
                        <button class="btn btn-primary" onclick="copyCurrentUrl()">Copiar Link</button>
                        <button class="btn btn-secondary" onclick="closeModals()">Fechar</button>
                    </div>
                </div>
            </div>

            <!-- MODAL: COMPARTILHAMENTO TEMPORÃRIO -->
            <div id="modalShare" class="modal-backdrop hidden">
                <div class="modal-box">
                    <button class="modal-close" onclick="closeModals()">âœ•</button>
                    <div class="modal-title">ðŸ”— Gerar Link TemporÃ¡rio</div>
                    <p style="color:var(--text-muted);font-size:13px;margin-bottom:12px">Crie um link de download com validade prÃ©-definida sem precisar compartilhar sua senha:</p>
                    <div class="field" style="margin-bottom:14px">
                        <label>Arquivo selecionado:</label>
                        <input id="shareFilePath" type="text" readonly>
                    </div>
                    <div class="field" style="margin-bottom:14px">
                        <label>Tempo de validade:</label>
                        <select id="shareDuration">
                            <option value="1">1 hora</option>
                            <option value="12">12 horas</option>
                            <option value="24" selected>24 horas (1 dia)</option>
                            <option value="72">3 dias</option>
                            <option value="168">7 dias (1 semana)</option>
                        </select>
                    </div>
                    <button class="btn btn-primary" onclick="executeCreateShare()" style="width:100%;justify-content:center">Gerar Link de Download</button>
                    <div id="shareResultBox" class="hidden" style="margin-top:14px">
                        <label style="font-size:12px;color:var(--primary);font-weight:600">Link gerado com sucesso:</label>
                        <input id="shareGeneratedUrl" type="text" readonly style="margin:6px 0;width:100%">
                        <button class="btn btn-secondary btn-sm" onclick="copyShareUrl()" style="width:100%;justify-content:center">Copiar Link de Compartilhamento</button>
                    </div>
                </div>
            </div>

            <!-- MODAL: PRÃ‰VIA DE MÃDIA (LIGHTBOX / PLAYER) -->
            <div id="modalMedia" class="modal-backdrop hidden">
                <div class="modal-box" style="max-width:800px">
                    <button class="modal-close" onclick="closeModals()">âœ•</button>
                    <div id="mediaTitle" class="modal-title">PrÃ©via de Arquivo</div>
                    <div id="mediaContainer" class="media-box"></div>
                    <div style="display:flex;justify-content:space-between;align-items:center;margin-top:14px">
                        <span id="mediaInfo" style="font-size:12px;color:var(--text-muted)"></span>
                        <a id="mediaDownloadBtn" class="btn btn-primary btn-sm" href="#" download>Baixar Arquivo</a>
                    </div>
                </div>
            </div>

            <script>
            const tabs=document.querySelectorAll(".tab-btn");
            const sections={files:document.querySelector("#files"),streaming:document.querySelector("#streaming"),drive:document.querySelector("#drive"),config:document.querySelector("#config"),live:document.querySelector("#live"),api:document.querySelector("#api")};
            tabs.forEach(btn=>{
                btn.onclick=()=>{
                    tabs.forEach(b=>b.classList.toggle("selected",b===btn));
                    const target=btn.dataset.tab;
                    Object.keys(sections).forEach(k=>{if(sections[k])sections[k].classList.toggle("hidden",k!==target)});
                    if(target==="streaming")loadMediaCatalog();
                    if(target==="drive")updateStorageStats();
                };
            });

            async function loadMediaCatalog(){
                const vBox=document.querySelector("#videoList");
                const aBox=document.querySelector("#audioList");
                const vCount=document.querySelector("#countVideos");
                const aCount=document.querySelector("#countAudios");
                try{
                    let r=await fetch("/api/media");
                    if(!r.ok) throw Error("Falha ao buscar mÃ­dias");
                    let data=await r.json();
                    vCount.textContent=data.totalVideos;
                    aCount.textContent=data.totalAudios;
                    vBox.replaceChildren();
                    aBox.replaceChildren();
                    if(!data.videos.length){
                        vBox.innerHTML='<div style="color:var(--text-muted);font-size:12px;padding:8px">Nenhum vÃ­deo encontrado (MP4, MKV, WebM, MOV, AVI).</div>';
                    } else {
                        data.videos.forEach(v=>{
                            let row=document.createElement("div");
                            row.className="file-item";
                            row.style.padding="8px";
                            let meta=document.createElement("div");
                            meta.className="file-meta";
                            let icon=document.createElement("span");
                            icon.className="icon";
                            icon.textContent="ðŸŽ¬";
                            let nameSpan=document.createElement("span");
                            nameSpan.style.fontWeight="600";
                            nameSpan.style.fontSize="13px";
                            nameSpan.textContent=v.name;
                            meta.append(icon,nameSpan);
                            let act=document.createElement("div");
                            act.className="file-actions";
                            let sz=document.createElement("span");
                            sz.className="file-size";
                            sz.textContent=v.sizeFormatted;
                            let btn=document.createElement("button");
                            btn.className="btn btn-primary btn-sm";
                            btn.textContent="â–¶ Reproduzir";
                            btn.onclick=()=>playMedia(v);
                            act.append(sz,btn);
                            row.append(meta,act);
                            vBox.append(row);
                        });
                    }
                    if(!data.audios.length){
                        aBox.innerHTML='<div style="color:var(--text-muted);font-size:12px;padding:8px">Nenhuma mÃºsica encontrada (MP3, WAV, OGG, FLAC, AAC).</div>';
                    } else {
                        data.audios.forEach(a=>{
                            let row=document.createElement("div");
                            row.className="file-item";
                            row.style.padding="8px";
                            let meta=document.createElement("div");
                            meta.className="file-meta";
                            let icon=document.createElement("span");
                            icon.className="icon";
                            icon.textContent="ðŸŽµ";
                            let nameSpan=document.createElement("span");
                            nameSpan.style.fontWeight="600";
                            nameSpan.style.fontSize="13px";
                            nameSpan.textContent=a.name;
                            meta.append(icon,nameSpan);
                            let act=document.createElement("div");
                            act.className="file-actions";
                            let sz=document.createElement("span");
                            sz.className="file-size";
                            sz.textContent=a.sizeFormatted;
                            let btn=document.createElement("button");
                            btn.className="btn btn-primary btn-sm";
                            btn.textContent="â–¶ Tocar";
                            btn.onclick=()=>playMedia(a);
                            act.append(sz,btn);
                            row.append(meta,act);
                            aBox.append(row);
                        });
                    }
                }catch(e){console.error(e)}
            }

            function playMedia(item){
                const view=document.querySelector("#playerView");
                const title=document.querySelector("#playerNowPlaying");
                const badge=document.querySelector("#playerFormatBadge");
                title.textContent="â–¶ "+item.name;
                badge.textContent=item.mime.toUpperCase();
                badge.style.display="inline-flex";
                view.replaceChildren();
                const isVideo=item.mime.startsWith("video");
                if(isVideo){
                    const v=document.createElement("video");
                    v.src=item.url;
                    v.controls=true;
                    v.autoplay=true;
                    v.playsInline=true;
                    v.style.maxWidth="100%";
                    v.style.maxHeight="420px";
                    v.style.borderRadius="8px";
                    view.append(v);
                } else {
                    const a=document.createElement("audio");
                    a.src=item.url;
                    a.controls=true;
                    a.autoplay=true;
                    a.style.width="100%";
                    a.style.padding="16px";
                    view.append(a);
                }
            }

            function copyM3uUrl(){
                const u=location.protocol+"//"+location.host+"/stream/playlist.m3u";
                navigator.clipboard.writeText(u);
                alert("URL da Playlist M3U copiada:\\n"+u+"\\nCole no VLC, Kodi ou Smart TV!");
            }

            function copyWebdavUrl(){
                const u=location.protocol+"//"+location.host+"/webdav";
                navigator.clipboard.writeText(u);
                alert("URL WebDAV copiada:\\n"+u+"\\nCole no Windows 'Mapear unidade de rede'!");
            }

            async function updateStorageStats(){
                const webdavLabel=document.querySelector("#webdavUrlDisplay");
                if(webdavLabel) webdavLabel.textContent=location.protocol+"//"+location.host+"/webdav";
                try{
                    let r=await fetch("/api/status");
                    let d=await r.json();
                    if(d.storageTotal && d.storageTotal>0){
                        let used=d.storageTotal - d.storageFree;
                        let pct=Math.round((used / d.storageTotal) * 100);
                        document.querySelector("#storageBar").style.width=pct+"%";
                        document.querySelector("#storageSummary").textContent=pct+"% utilizado ("+formatSize(used)+" de "+formatSize(d.storageTotal)+")";
                        document.querySelector("#storageUsedDetail").textContent="Arquivos AndServer: "+formatSize(d.storageHosted||0);
                        document.querySelector("#storageFreeDetail").textContent="Livre no celular: "+formatSize(d.storageFree);
                    }
                }catch(e){console.error(e)}
            }

            function closeModals(){
                document.querySelectorAll(".modal-backdrop").forEach(m=>m.classList.add("hidden"));
                document.querySelector("#mediaContainer").replaceChildren();
            }

            function showQrModal(){
                const curUrl=location.protocol+"//"+location.host;
                document.querySelector("#qrImage").src="/api/qrcode?text="+encodeURIComponent(curUrl);
                document.querySelector("#qrUrlLabel").textContent=curUrl;
                document.querySelector("#modalQr").classList.remove("hidden");
            }

            function copyCurrentUrl(){
                navigator.clipboard.writeText(location.protocol+"//"+location.host);
                alert("EndereÃ§o copiado para a Ã¡rea de transferÃªncia!");
            }

            function openShareModal(path){
                document.querySelector("#shareFilePath").value=path;
                document.querySelector("#shareResultBox").classList.add("hidden");
                document.querySelector("#modalShare").classList.remove("hidden");
            }

            async function executeCreateShare(){
                const path=document.querySelector("#shareFilePath").value;
                const hours=Number(document.querySelector("#shareDuration").value);
                try{
                    let r=await fetch("/api/share",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({path,hours})});
                    let data=await r.json();
                    if(!r.ok) throw Error(data.error||"Erro ao gerar link");
                    const fullUrl=location.protocol+"//"+location.host+data.url;
                    document.querySelector("#shareGeneratedUrl").value=fullUrl;
                    document.querySelector("#shareResultBox").classList.remove("hidden");
                }catch(e){alert(e.message)}
            }

            function copyShareUrl(){
                const u=document.querySelector("#shareGeneratedUrl").value;
                navigator.clipboard.writeText(u);
                alert("Link copiado! Qualquer pessoa pode usar este link para baixar atÃ© o prazo expirar.");
            }

            function openMediaPreview(name,url,sizeStr){
                const container=document.querySelector("#mediaContainer");
                container.replaceChildren();
                document.querySelector("#mediaTitle").textContent=name;
                document.querySelector("#mediaInfo").textContent="Tamanho: "+sizeStr;
                const dlBtn=document.querySelector("#mediaDownloadBtn");
                dlBtn.href=url;
                dlBtn.setAttribute("download",name);

                const ext=name.split('.').pop().toLowerCase();
                if(["jpg","jpeg","png","gif","webp","svg"].includes(ext)){
                    const img=document.createElement("img");
                    img.src=url;
                    container.append(img);
                } else if(["mp4","webm","mkv","mov"].includes(ext)){
                    const video=document.createElement("video");
                    video.src=url;
                    video.controls=true;
                    video.autoplay=true;
                    container.append(video);
                } else if(["mp3","wav","ogg","aac","flac"].includes(ext)){
                    const audio=document.createElement("audio");
                    audio.src=url;
                    audio.controls=true;
                    audio.autoplay=true;
                    container.append(audio);
                } else {
                    location.href=url;
                    return;
                }
                document.querySelector("#modalMedia").classList.remove("hidden");
            }

            const dropzone=document.querySelector("#dropzone");
            ["dragenter","dragover"].forEach(name=>{dropzone.addEventListener(name,e=>{e.preventDefault();dropzone.classList.add("dragover")})});
            ["dragleave","drop"].forEach(name=>{dropzone.addEventListener(name,e=>{e.preventDefault();dropzone.classList.remove("dragover")})});
            dropzone.addEventListener("drop",e=>{
                if(e.dataTransfer&&e.dataTransfer.files.length){
                    document.querySelector("#picker").files=e.dataTransfer.files;
                    uploadFiles();
                }
            });

            async function refresh(){
                let box=document.querySelector("#filelist");
                try{
                    let r=await fetch("/api/files");
                    if(!r.ok)throw Error("NÃ£o foi possÃ­vel carregar os arquivos");
                    let files=await r.json();
                    box.replaceChildren();
                    if(!files.length){
                        box.innerHTML='<div style="padding:20px;text-align:center;color:var(--text-muted)">Nenhum arquivo armazenado ainda. Use a Ã¡rea acima para enviar.</div>';
                        return;
                    }
                    for(const item of files){
                        let row=document.createElement("div");
                        row.className="file-item";
                        let meta=document.createElement("div");
                        meta.className="file-meta";
                        let icon=document.createElement("span");
                        icon.className="icon";
                        icon.textContent=item.directory?"ðŸ“":getFileIcon(item.name);

                        let title=document.createElement("a");
                        title.className="clickable-title";
                        title.textContent=item.name;
                        if(item.directory){
                            title.href=item.url;
                        } else {
                            title.onclick=()=>openMediaPreview(item.name,item.url,formatSize(item.size));
                        }
                        meta.append(icon,title);

                        let actions=document.createElement("div");
                        actions.className="file-actions";

                        let size=document.createElement("span");
                        size.className="file-size";
                        size.textContent=item.directory?"Pasta":formatSize(item.size);
                        actions.append(size);

                        if(item.directory){
                            let zipBtn=document.createElement("a");
                            zipBtn.className="btn btn-secondary btn-sm";
                            zipBtn.href="/api/zip?path="+encodePath(item.name);
                            zipBtn.textContent="ðŸ“¦ ZIP";
                            actions.append(zipBtn);
                        } else {
                            let shareBtn=document.createElement("button");
                            shareBtn.className="btn btn-secondary btn-sm";
                            shareBtn.textContent="ðŸ”— Link";
                            shareBtn.onclick=()=>openShareModal(item.name);

                            let dl=document.createElement("a");
                            dl.className="btn btn-secondary btn-sm";
                            dl.href=item.url;
                            dl.setAttribute("download",item.name);
                            dl.textContent="â¬‡ï¸";

                            actions.append(shareBtn,dl);
                        }

                        let del=document.createElement("button");
                        del.className="btn btn-danger";
                        del.textContent="Excluir";
                        del.onclick=()=>removeFile(item.name,item.directory);
                        actions.append(del);

                        row.append(meta,actions);
                        box.append(row);
                    }
                    filterRows();
                }catch(e){box.textContent=e.message}
            }

            function getFileIcon(n){
                let ext=n.split('.').pop().toLowerCase();
                if(["jpg","jpeg","png","gif","webp","svg"].includes(ext)) return "ðŸ–¼ï¸";
                if(["mp3","wav","ogg","aac","flac"].includes(ext)) return "ðŸŽµ";
                if(["mp4","mkv","webm","mov","avi"].includes(ext)) return "ðŸŽ¬";
                if(["zip","rar","7z","tar","gz"].includes(ext)) return "ðŸ“¦";
                if(["pdf","doc","docx","txt","md"].includes(ext)) return "ðŸ“„";
                return "ðŸ“Ž";
            }

            function formatSize(n){
                return n<1024?n+" B":n<1048576?(n/1024).toFixed(1)+" KB":(n/1048576).toFixed(1)+" MB";
            }
            function encodePath(s){return s.split("/").map(encodeURIComponent).join("/");}

            async function uploadFiles(){
                let files=document.querySelector("#picker").files;
                let msg=document.querySelector("#message");
                for(const f of files){
                    msg.textContent="Enviando "+f.name+"...";
                    let r=await fetch("/api/files/"+encodePath(f.name),{method:"PUT",body:f});
                    msg.textContent=r.ok?"Enviado com sucesso: "+f.name:"Falha no envio de "+f.name;
                }
                await refresh();
            }

            async function removeFile(name,directory){
                if(!confirm((directory?"Excluir a pasta e todo o conteÃºdo de ":"Excluir o arquivo ")+name+"?")) return;
                let r=await fetch("/api/files/"+encodePath(name),{method:"DELETE"});
                document.querySelector("#message").textContent=r.ok?"Item removido com sucesso.":"Falha ao remover item.";
                await refresh();
            }

            async function createFolder(){
                let name=prompt("Nome da nova pasta:");
                if(!name)return;
                let r=await fetch("/api/files/"+encodePath(name),{method:"MKCOL"});
                document.querySelector("#message").textContent=r.ok?"Pasta criada!":"NÃ£o foi possÃ­vel criar a pasta.";
                await refresh();
            }

            function filterRows(){
                let term=document.querySelector("#search").value.toLowerCase();
                document.querySelectorAll("#filelist .file-item").forEach(row=>{
                    row.classList.toggle("hidden",!row.textContent.toLowerCase().includes(term));
                });
            }

            function formatTraffic(n){
                return n<1024?n+" B":n<1048576?(n/1024).toFixed(1)+" KB":n<1073741824?(n/1048576).toFixed(1)+" MB":(n/1073741824).toFixed(1)+" GB";
            }

            async function refreshStatus(){
                try{
                    let s=await(await fetch("/api/status")).json();
                    document.querySelector("#requests").textContent=s.requests;
                    document.querySelector("#clients").textContent=s.clients;
                    document.querySelector("#incoming").textContent=formatTraffic(s.bytesReceived);
                    document.querySelector("#outgoing").textContent=formatTraffic(s.bytesSent);
                    document.querySelector("#statLocalIp").textContent=location.hostname;
                    if(s.publicIp) document.querySelector("#statPublicIp").textContent=s.publicIp;
                }catch(e){}
            }

            async function loadConfig(){
                try{
                    let c=await(await fetch("/api/config")).json();
                    document.querySelector("#cfgType").value=c.type;
                    document.querySelector("#cfgPort").value=c.port;
                    document.querySelector("#cfgWorkers").value=c.workers;
                    if(c.networkScope) document.querySelector("#cfgNetworkScope").value=c.networkScope;
                    document.querySelectorAll("input[name=filter]").forEach(x=>x.checked=c.filters.includes(x.value));
                    if(c.filters.includes("all")) document.querySelectorAll("input[name=filter]").forEach(x=>x.checked=x.value==="all");
                }catch(e){}
            }

            document.querySelectorAll("input[name=filter]").forEach(x=>x.onchange=()=>{
                let all=document.querySelector('input[name=filter][value="all"]');
                if(x.value==="all"&&x.checked) document.querySelectorAll("input[name=filter]").forEach(y=>y.checked=y===x);
                else if(x.value!=="all"&&x.checked) all.checked=false;
            });

            document.querySelector("#settings").onsubmit=async e=>{
                e.preventDefault();
                let filters=[...document.querySelectorAll("input[name=filter]:checked")].map(x=>x.value);
                let data={
                    type:document.querySelector("#cfgType").value,
                    port:Number(document.querySelector("#cfgPort").value),
                    workers:Number(document.querySelector("#cfgWorkers").value),
                    networkScope:document.querySelector("#cfgNetworkScope").value,
                    filters
                };
                let pwd=document.querySelector("#cfgPassword").value;
                if(pwd) data.password=pwd;
                if(document.querySelector("#removePassword").checked) data.password="";
                let result=document.querySelector("#configMessage");
                try{
                    let r=await fetch("/api/config",{method:"PUT",headers:{"Content-Type":"application/json"},body:JSON.stringify(data)});
                    let res=await r.json();
                    if(!r.ok)throw Error(res.error||"Falha ao salvar configuraÃ§Ã£o");
                    result.textContent="ConfiguraÃ§Ã£o salva! Reiniciando...";
                    setTimeout(()=>{
                        let p=Number(data.port);
                        location.href=location.protocol+"//"+location.hostname+(p===80?"":":"+p)+"/";
                    },1400);
                }catch(err){result.textContent=err.message}
            };

            function connectLive(){
                let ws=new WebSocket("wss://"+location.host+"/ws?token=$webSocketToken");
                ws.onopen=()=>document.querySelector("#wsStatus").textContent="â— ConexÃ£o WSS ativa com o servidor";
                ws.onmessage=e=>{
                    try{
                        let data=JSON.parse(e.data);
                        let row=document.createElement("li");
                        let time=new Date().toLocaleTimeString();
                        row.textContent="["+time+"] "+(data.message||JSON.stringify(data));
                        let list=document.querySelector("#liveEvents");
                        list.prepend(row);
                        while(list.children.length>30) list.lastElementChild.remove();
                    }catch(err){}
                };
                ws.onerror=()=>document.querySelector("#wsStatus").textContent="Falha na conexÃ£o WebSocket";
                ws.onclose=()=>{
                    document.querySelector("#wsStatus").textContent="Reconectando WebSocket...";
                    setTimeout(connectLive,3000);
                };
            }

            const defaultTab = "${if (type == "streaming") "streaming" else if (type == "drive") "drive" else "files"}";
            const defaultBtn = document.querySelector('.tab-btn[data-tab="' + defaultTab + '"]');
            if (defaultBtn) defaultBtn.click();
            refresh();refreshStatus();loadConfig();connectLive();setInterval(refreshStatus,3000);
            </script>
        </body></html>"""
    }
}
