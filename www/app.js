/**
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

        if (apiOutput) apiOutput.innerHTML = `<code>Consultando ${url}...</code>`;
        if (responseStatus) {
            responseStatus.textContent = 'Enviando...';
            responseStatus.style.color = 'var(--text-subtle)';
        }

        const start = performance.now();
        try {
            const res = await fetch(url);
            const duration = Math.round(performance.now() - start);

            if (responseTimer) responseTimer.textContent = `${duration} ms`;
            if (pingEl) pingEl.textContent = `${duration} ms`;

            if (responseStatus) {
                responseStatus.textContent = `${res.status} ${res.statusText || 'OK'}`;
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
                apiOutput.innerHTML = `<code>${escapeHtml(text)}</code>`;
            }
        } catch (err) {
            const duration = Math.round(performance.now() - start);
            if (responseTimer) responseTimer.textContent = `${duration} ms`;
            if (responseStatus) {
                responseStatus.textContent = 'ERRO';
                responseStatus.style.color = '#ef4444';
            }
            lastResult = err.message;
            if (apiOutput) {
                apiOutput.innerHTML = `<code>Falha na requisição: ${escapeHtml(err.message)}\n\nCertifique-se de que o servidor AndServer está em execução.</code>`;
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
