'use strict';
const byId = id => document.getElementById(id);
const statusLine = text => { byId('status').textContent = text; };
const encode = encodeURIComponent;
const accountOnly = new URL(location.href).searchParams.get('account') === '1';
const hasSession = document.cookie.split(';').some(cookie => cookie.trim().startsWith('PVEAuthCookie='));
let csrf = hasSession ? localStorage.getItem('modular.csrf') || '' : '';
let user = localStorage.getItem('modular.account') || '';
let challenge = '';
let resources = [];
const openNodes = new Set();
const openGuests = new Set();
const tasks = new Map();
let readSerial = 0;
let consoleSession = null;
let confirming = false;
const native = window.ModularProxmox;
const tokenMode = native?.hasToken() === true;
const callbacks = new Map();
let requestId = 0;
let openingShell = false;
window.modularResult = (id, status, body) => callbacks.get(id)?.(status, body);

function nativeResult(send, timeout = 25000) {
    return new Promise((resolve, reject) => {
        const id = String(++requestId);
        const timer = setTimeout(() => {
            callbacks.delete(id);
            reject(Error('Request timed out.'));
        }, timeout);
        callbacks.set(id, (status, body) => {
            clearTimeout(timer);
            callbacks.delete(id);
            if (status >= 200 && status < 300) resolve(body);
            else reject(Object.assign(Error(body), {status}));
        });
        send(id);
    });
}

async function api(path, data, signal, consoleRequest = false) {
    if (tokenMode && !consoleRequest && path !== '/access/ticket') {
        const body = await nativeResult(id => native.request(id, data ? 'POST' : 'GET', path));
        return JSON.parse(body).data;
    }
    const options = { credentials: 'same-origin', redirect: 'error', cache: 'no-store', signal };
    if (data) {
        options.method = 'POST';
        options.body = new URLSearchParams(data);
        if (csrf) options.headers = { CSRFPreventionToken: csrf };
    }
    const response = await fetch('/api2/json' + path, options);
    if (!response.ok) {
        const error = Error(response.status + ' ' + response.statusText);
        error.status = response.status;
        throw error;
    }
    return (await response.json()).data;
}

function showLogin(message) {
    csrf = ''; challenge = '';
    localStorage.removeItem('modular.csrf');
    byId('accountSession').hidden = true;
    byId('login').hidden = false;
    byId('account').value = user;
    byId('account').readOnly = false;
    byId('password').required = true;
    byId('password').value = ''; byId('otp').value = '';
    byId('otpLabel').hidden = true; byId('passwordLabel').hidden = false;
    statusLine(message);
}

function showAccount() {
    byId('login').hidden = true;
    byId('accountSession').hidden = false;
    byId('signedInAs').textContent = 'Signed in as ' + user;
    statusLine('');
}

function openSettings(message, forAccount, keepNodes = false) {
    byId('openSettings').hidden = false;
    byId('openSettings').textContent = forAccount ? 'Open Proxmox to sign in' : 'Open Proxmox';
    byId('openSettings').onclick = () => native?.openSettings(forAccount);
    byId('cluster').hidden = !keepNodes;
    statusLine(message);
}

byId('login').onsubmit = async event => {
    event.preventDefault();
    const button = byId('signIn');
    button.disabled = true;
    try {
        const name = byId('account').value.trim();
        const body = {username:name, password:byId('password').value, 'new-format':1};
        if (challenge) Object.assign(body, {password:'totp:' + byId('otp').value.trim(), 'tfa-challenge':challenge});
        const ticket = await api('/access/ticket', body);
        byId('password').value = '';
        if (ticket.NeedTFA || ticket.ticket?.startsWith('PVE:tfa!')) {
            challenge = ticket.ticket;
            byId('password').required = false;
            byId('passwordLabel').hidden = true; byId('otpLabel').hidden = false;
            byId('account').readOnly = true;
            byId('otp').focus(); statusLine('Enter your TOTP code.');
            return;
        }
        if (!ticket.ticket || !ticket.CSRFPreventionToken) throw Error('No session ticket returned.');
        csrf = ticket.CSRFPreventionToken; user = ticket.username;
        document.cookie = 'PVEAuthCookie=' + encode(ticket.ticket) + '; Max-Age=7200; Path=/; Secure; SameSite=Strict';
        localStorage.setItem('modular.csrf', csrf); localStorage.setItem('modular.account', user);
        byId('otp').value = ''; challenge = '';
        showAccount();
    } catch (error) { statusLine('Sign-in failed. ' + error.message); }
    finally { button.disabled = false; }
};
byId('logout').onclick = () => {
    document.cookie = 'PVEAuthCookie=; Max-Age=0; Path=/; Secure; SameSite=Strict';
    showLogin('');
};

function metric(label, used, total, text) {
    const row = element('span', null, 'metric');
    row.append(element('span', label), element('b', text));
    if (Number.isFinite(used) && Number.isFinite(total) && total > 0) {
        const meter = element('meter');
        meter.max = total;
        meter.value = Math.max(0, Math.min(used, total));
        meter.setAttribute('aria-label', label);
        row.append(meter);
    }
    return row;
}
function memoryUsage(used, total) {
    if (used == null && total == null) return '—';
    const unit = (total ?? used) >= 1073741824 ? 1073741824 : 1048576;
    const format = value => value == null ? '—' : (value / unit).toFixed(1);
    return format(used) + ' / ' + format(total) + (unit === 1073741824 ? ' GiB' : ' MiB');
}
function element(tag, text, className) {
    const el = document.createElement(tag);
    if (text != null) el.textContent = text;
    if (className) el.className = className;
    return el;
}
function control(label, action, disabled = false) {
    const button = element('button', label);
    button.type = 'button'; button.disabled = disabled; button.onclick = action;
    return button;
}
function resourceRow(item, title, opened, id) {
    const button = control('', () => {
        if (!opened.delete(id)) opened.add(id);
        draw();
    });
    button.className = 'row';
    button.setAttribute('aria-expanded', String(opened.has(id)));
    const heading = element('span', null, 'resource-heading');
    const status = element('small', item.status || 'Unknown', 'resource-status');
    status.dataset.active = String(['online', 'running'].includes(item.status));
    heading.append(element('strong', (opened.has(id) ? '▾  ' : '▸  ') + title), status);
    const metrics = element('span', null, 'metrics');
    metrics.append(metric('CPU', item.cpu, 1, item.cpu == null ? '—' : (item.cpu * 100).toFixed(1) + '%'),
        metric('RAM', item.mem, item.maxmem, memoryUsage(item.mem, item.maxmem)));
    button.append(heading, metrics);
    return button;
}
function base(item) {
    const node = '/nodes/' + encode(item.node);
    return item.type === 'lxc' ? node + '/lxc/' + encode(item.vmid) : node;
}
function draw() {
    confirming = false;
    const fragment = document.createDocumentFragment();
    const nodes = resources.filter(r => r.type === 'node').sort((a,b) => a.node.localeCompare(b.node));
    if (!nodes.length) fragment.append(element('p', 'No nodes visible to this account.'));
    for (const node of nodes) {
        const group = element('article', null, 'node');
        group.append(resourceRow(node, node.node, openNodes, node.node));
        if (openNodes.has(node.node)) {
            group.append(control('Node shell', () => startShell(node), node.status !== 'online'));
            const guests = resources.filter(r => r.type === 'lxc' && r.node === node.node).sort((a,b) => a.vmid - b.vmid);
            if (!guests.length) group.append(element('p', 'No visible containers.'));
            for (const guest of guests) {
                const row = element('section', null, 'guest');
                row.append(resourceRow(guest, guest.vmid + ' · ' + (guest.name || 'Container'), openGuests, guest.id));
                if (openGuests.has(guest.id)) {
                    const actions = element('div', null, 'actions');
                    const busy = tasks.has(guest.id);
                    actions.append(control('Shell', () => startShell(guest), busy || guest.status !== 'running'));
                    for (const [label, action] of guest.status === 'running' ? [['Restart','reboot'],['Stop','stop']] : [['Start','start']])
                        actions.append(control(label, () => power(guest, label, action), busy || node.status !== 'online'));
                    row.append(actions);
                    if (busy) row.append(element('small', tasks.get(guest.id).message));
                }
                group.append(row);
            }
        }
        fragment.append(group);
    }
    byId('resources').replaceChildren(fragment);
}

async function refresh() {
    const serial = ++readSerial;
    try {
        const items = await api('/cluster/resources');
        if (serial !== readSerial) return;
        resources = items;
        byId('login').hidden = true; byId('cluster').hidden = !!consoleSession;
        if (!confirming) draw();
    } catch (error) {
        if (serial !== readSerial) return;
        if (error.status === 401 && !tokenMode) {
            csrf = '';
            openSettings('Sign in to see your Proxmox nodes.', true);
        } else if (tokenMode && [401, 403].includes(error.status)) {
            openSettings('Check your API token and its permissions in Proxmox.', false);
        } else {
            openSettings('Could not load nodes. ' + error.message, false, resources.length > 0);
        }
    }
}

// WebView has no default JavaScript dialog UI. Confirm inline on the target row.
function power(guest, label, action) {
    if (tasks.has(guest.id)) return;
    const row = [...byId('resources').querySelectorAll('.guest')].find(el =>
        el.querySelector('strong')?.textContent.startsWith('▾  ' + guest.vmid + ' · '));
    if (!row) return;
    confirming = true;
    const actions = row.querySelector('.actions');
    actions.replaceChildren(element('span', label + ' CT ' + guest.vmid + '?'),
        control('Cancel', draw), control('Confirm ' + label.toLowerCase(), () => submitPower(guest, label, action)));
}
async function submitPower(guest, label, action) {
    if (tasks.has(guest.id)) return;
    const task = {message:label + ' requested…'};
    tasks.set(guest.id, task); draw();
    try {
        const upid = await api(base(guest) + '/status/' + action, {});
        for (let attempt = 0; attempt < 120; attempt++) {
            if (tasks.get(guest.id) !== task) return;
            const result = await api('/nodes/' + encode(guest.node) + '/tasks/' + encode(upid) + '/status');
            if (result.status === 'stopped') {
                if (result.exitstatus !== 'OK') throw Error(result.exitstatus || 'Task failed');
                statusLine(label + ' completed for CT ' + guest.vmid + '.');
                return;
            }
            await new Promise(resolve => setTimeout(resolve, 1500));
        }
        statusLine('Task still running on the server. Refresh to see the current state.');
    } catch (error) { statusLine(label + ' failed for CT ' + guest.vmid + '. ' + error.message); }
    finally {
        if (tasks.get(guest.id) === task) { tasks.delete(guest.id); draw(); await refresh(); }
    }
}

function endShell() {
    const s = consoleSession;
    consoleSession = null;
    if (s) {
        clearInterval(s.ping);
        s.resize?.disconnect();
        s.abort.abort();
        s.socket?.close(); s.term?.dispose();
    }
    byId('terminal').replaceChildren();
    byId('input').value = '';
    byId('shell').hidden = true;
}
function sendInput(data) {
    const s = consoleSession;
    if (s?.ready && s.socket.readyState === WebSocket.OPEN)
        s.socket.send('0:' + new TextEncoder().encode(data).length + ':' + data);
}
async function startShell(item) {
    if (openingShell || consoleSession) return;
    if (!csrf) {
        openSettings('Sign in with a Proxmox account to open a shell.', true, resources.length > 0);
        return;
    }
    if (item.type === 'node' && native) {
        openingShell = true;
        try { await nativeResult(id => native.unlockNode(id, item.node), 120000); }
        catch (error) { statusLine(error.message); return; }
        finally { openingShell = false; }
    }
    const s = { ready:false, abort:new AbortController() };
    consoleSession = s;
    if (!history.state?.shell) history.pushState({shell:true}, '');
    byId('cluster').hidden = true; byId('shell').hidden = false;
    byId('target').textContent = item.type === 'lxc' ? 'CT ' + item.vmid + ' · ' + item.node : item.node;
    byId('send').disabled = true; byId('interrupt').disabled = true;
    statusLine('Connecting shell…');
    try {
        if (!window.Terminal) throw Error('The server’s xterm.js terminal asset could not be loaded.');
        const term = s.term = new Terminal({scrollback:4000, fontSize:13, cursorBlink:true});
        term.open(byId('terminal'));
        term.onData(sendInput);
        const proxy = await api(base(item) + '/termproxy', {}, s.abort.signal, true);
        if (s !== consoleSession) return;
        const ws = s.socket = new WebSocket('wss://' + location.host + '/api2/json' + base(item) +
            '/vncwebsocket?' + new URLSearchParams({port:proxy.port, vncticket:proxy.ticket}), 'binary');
        ws.binaryType = 'arraybuffer';
        ws.onopen = () => { if (s === consoleSession) ws.send((proxy.user || user) + ':' + proxy.ticket + '\n'); };
        function resize() {
            const box = byId('terminal').getBoundingClientRect();
            const cols = Math.max(12, Math.floor((box.width - 12) / 8));
            const rows = Math.max(2, Math.floor((box.height - 8) / 16));
            term.resize(cols, rows);
            if (s.ready && ws.readyState === WebSocket.OPEN) ws.send('1:' + cols + ':' + rows + ':');
        }
        ws.onmessage = event => {
            if (s !== consoleSession) return;
            let data = new Uint8Array(event.data);
            if (!s.ready) {
                if (data[0] !== 79 || data[1] !== 75) { statusLine('Console authentication failed.'); ws.close(); return; }
                data = data.subarray(2); s.ready = true;
                byId('send').disabled = false; byId('interrupt').disabled = false; statusLine('');
                s.resize = new ResizeObserver(resize); s.resize.observe(byId('terminal')); resize();
                s.ping = setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send('2'); }, 30000);
            }
            if (data.length) term.write(data);
        };
        ws.onclose = () => {
            if (s !== consoleSession) return;
            s.ready = false; clearInterval(s.ping); byId('send').disabled = true; byId('interrupt').disabled = true;
            statusLine('Shell closed. You can still copy its output.');
        };
        ws.onerror = () => { if (s === consoleSession) statusLine('Console connection failed.'); };
    } catch (error) {
        if (s !== consoleSession) return;
        if (error.status === 401) {
            endShell();
            csrf = '';
            openSettings('The console session expired. Sign in again in Proxmox.', true, resources.length > 0);
        } else statusLine('Could not open shell. ' + error.message);
    }
}
byId('command').onsubmit = event => {
    event.preventDefault();
    if (!consoleSession?.ready) return;
    const input = byId('input');
    sendInput(input.value.replace(/\r?\n/g,'\r') + '\r');
    input.value = ''; input.focus();
};
byId('interrupt').onclick = () => sendInput('\x03');
byId('copy').onclick = async () => {
    const term = consoleSession?.term;
    if (!term) return;
    const buffer = term.buffer.active;
    let output = '';
    for (let i = 0; i < buffer.length; i++) {
        const line = buffer.getLine(i);
        if (line) output += (i && !line.isWrapped ? '\n' : '') + line.translateToString(true);
    }
    const text = term.getSelection() || output.trimEnd();
    if (!text) { statusLine('No shell output yet.'); return; }
    try { await navigator.clipboard.writeText(text); statusLine('Output copied.'); }
    catch {
        const field = element('textarea'); field.value = text;
        byId('shell').append(field); field.select();
        if (document.execCommand('copy')) { field.remove(); statusLine('Output copied.'); }
        else { field.readOnly = true; statusLine('Output selected. Use the system Copy action.'); }
    }
};
function leaveShell() { endShell(); byId('cluster').hidden = false; statusLine(''); refresh(); }
window.addEventListener('popstate', () => { if (consoleSession) leaveShell(); });
byId('closeShell').onclick = () => { if (history.state?.shell) history.back(); else leaveShell(); };
setInterval(() => {
    if (!accountOnly && !document.hidden && (tokenMode || csrf) && !openingShell && !consoleSession &&
        !confirming && tasks.size === 0) refresh();
}, 15000);
window.addEventListener('pagehide', endShell);
if (accountOnly) {
    if (!csrf) showLogin('');
    else api('/access/permissions').then(showAccount).catch(() => showLogin('Sign in to renew your session.'));
} else if (tokenMode || csrf) {
    refresh();
} else {
    openSettings('Sign in or add an API token in the Proxmox app.', true);
}
