import { MAX_KEY_BYTES, validateMapping, restoreReport } from './tokenization.mjs';

const $ = id => document.getElementById(id);
const input = $('fileInput');
const dropzone = $('dropzone');
const policies = [...document.querySelectorAll('input[name="policies"]')];
const maxFileSize = 20 * 1024 * 1024;
let selectedFile = null;
let mapping = null;
let sanitizedText = '';
let busy = false;
let requestVersion = 0;
let keyVersion = 0;
let controller = null;

function status(id, message = '', isError = false) {
    const node = $(id);
    node.textContent = message;
    node.className = message ? `result ${isError ? 'error' : 'success'}` : 'result';
}

function updateControls() {
    $('submitButton').disabled = busy || !selectedFile || !policies.some(item => item.checked);
    $('restoreButton').disabled = busy || !mapping || !$('reportInput').value.trim();
    $('keyInput').disabled = busy;
    $('spinner').style.display = busy ? 'inline-block' : 'none';
    $('submitIcon').style.display = busy ? 'none' : 'block';
    $('buttonText').textContent = busy ? 'Local AI is finding sensitive values…' : 'Tokenize & generate safe text (Encrypt)';
}

function clearRestored() {
    $('restoredReport').textContent = '';
    $('restoredPanel').hidden = true;
    status('restoreStatus');
}

function invalidateSession() {
    requestVersion++;
    keyVersion++;
    controller?.abort();
    controller = null;
    busy = false;
    mapping = null;
    sanitizedText = '';
    $('sanitizedText').textContent = '';
    $('mappingPreview').textContent = '';
    $('mappingDetails').open = false;
    $('outputs').hidden = true;
    $('keyInput').value = '';
    $('reportInput').value = '';
    $('keyStatus').textContent = 'No local key loaded. Tokenize a PDF or load a saved JSON key.';
    clearRestored();
    status('result');
    updateControls();
}

function formatSize(bytes) {
    return bytes < 1048576 ? `${(bytes / 1024).toFixed(0)} KB` : `${(bytes / 1048576).toFixed(1)} MB`;
}

function setFile(file) {
    invalidateSession();
    selectedFile = null;
    $('fileCard').style.display = 'none';
    if (file) {
        if (!file.name.toLowerCase().endsWith('.pdf') || (file.type && file.type !== 'application/pdf')) {
            status('result', 'Please choose a PDF file.', true);
            input.value = '';
        } else if (!file.size || file.size > maxFileSize) {
            status('result', !file.size ? 'Choose a non-empty PDF.' : 'This PDF exceeds 20 MB.', true);
            input.value = '';
        } else {
            selectedFile = file;
            $('fileName').textContent = file.name;
            $('fileSize').textContent = formatSize(file.size);
            $('fileCard').style.display = 'flex';
        }
    }
    updateControls();
}

function showMapping(source) {
    const count = Object.keys(mapping).length;
    $('mappingPreview').textContent = JSON.stringify(mapping, null, 2);
    $('mappingCount').textContent = `(${count} ${count === 1 ? 'entry' : 'entries'})`;
    $('keyStatus').textContent = `${source}: ${count} ${count === 1 ? 'identity' : 'identities'}. This key replaces any previous key in this tab.`;
    $('mappingDetails').open = false;
    updateControls();
}

async function tokenize() {
    if (busy || !selectedFile) return;
    const chosen = policies.filter(item => item.checked).map(item => item.value);
    if (!chosen.length) return status('result', 'Choose at least one detection category.', true);
    const file = selectedFile;
    invalidateSession();
    const version = requestVersion;
    controller = new AbortController();
    busy = true;
    updateControls();
    const form = new FormData();
    form.append('file', file);
    chosen.forEach(policy => form.append('policies', policy));
    try {
        const response = await fetch('/api/safedocs/tokenize', {
            method: 'POST', body: form, signal: controller.signal, cache: 'no-store'
        });
        let data;
        try { data = await response.json(); } catch { throw new Error('The service returned an unreadable response.'); }
        if (!response.ok) throw new Error(data.message || `Tokenization failed (HTTP ${response.status}).`);
        if (version !== requestVersion) return;
        if (typeof data.sanitizedText !== 'string' || !Number.isInteger(data.replacementCount) || data.replacementCount < 0) {
            throw new Error('The service returned an invalid tokenization result.');
        }
        mapping = validateMapping(data.mapping);
        sanitizedText = data.sanitizedText;
        $('sanitizedText').textContent = sanitizedText;
        showMapping('Current document key');
        $('outputs').hidden = false;
        const message = data.replacementCount
            ? `${data.replacementCount} sensitive occurrences replaced. Review the text before sharing; download your local key to restore reports later.`
            : 'No selected sensitive values were detected. The text is unchanged. Review it carefully before sharing.';
        status('result', message);
    } catch (error) {
        if (version === requestVersion && error.name !== 'AbortError') {
            status('result', error.message || 'Tokenization failed. Check the local model configuration.', true);
        }
    } finally {
        if (version === requestVersion) { busy = false; controller = null; updateControls(); }
    }
}

async function loadKey(file) {
    if (!file || busy) return;
    const version = ++keyVersion;
    // Clear the previous key before parsing: an invalid import must never silently reuse it.
    mapping = null;
    sanitizedText = '';
    $('outputs').hidden = true;
    $('mappingPreview').textContent = '';
    $('sanitizedText').textContent = '';
    $('keyStatus').textContent = 'Loading local key…';
    clearRestored();
    status('result');
    updateControls();
    try {
        if (!file.size || file.size > MAX_KEY_BYTES) throw new Error('Choose a non-empty JSON key no larger than 2 MB.');
        const content = await file.text();
        if (version !== keyVersion) return;
        let parsed;
        try { parsed = JSON.parse(content); } catch { throw new Error('This file is not valid JSON.'); }
        mapping = validateMapping(parsed);
        showMapping('Imported local key');
        status('restoreStatus', 'Local key loaded. Paste the matching AI report and restore it.');
    } catch (error) {
        if (version !== keyVersion) return;
        $('keyStatus').textContent = 'No local key loaded.';
        status('restoreStatus', error.message, true);
    } finally {
        if (version === keyVersion) { $('keyInput').value = ''; updateControls(); }
    }
}

function downloadKey() {
    if (!mapping) return;
    const blob = new Blob([JSON.stringify(mapping, null, 2)], { type: 'application/json' });
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = 'safedocs-local-key.json';
    document.body.append(link);
    link.click();
    link.remove();
    window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}

function restore() {
    clearRestored();
    try {
        const result = restoreReport($('reportInput').value, mapping);
        $('restoredReport').textContent = result.text;
        $('restoredPanel').hidden = false;
        const unresolved = result.unknownTokens.length
            ? ` ${result.unknownTokens.length} unknown ${result.unknownTokens.length === 1 ? "token was" : "tokens were"} left unchanged. Check that you loaded the matching key.` : '';
        status('restoreStatus', `${result.restoredCount} token occurrences restored locally.${unresolved}`);
    } catch (error) { status('restoreStatus', error.message, true); }
}

async function copyText(text, statusId) {
    try {
        await navigator.clipboard.writeText(text);
        status(statusId, 'Copied to clipboard.');
    } catch {
        status(statusId, 'Clipboard access was unavailable. Select the displayed text and copy it manually.', true);
    }
}

dropzone.addEventListener('click', event => {
    if (!event.target.closest('#removeFile')) input.click();
});
dropzone.addEventListener('keydown', event => {
    if (event.target === dropzone && (event.key === 'Enter' || event.key === ' ')) {
        event.preventDefault(); input.click();
    }
});
input.addEventListener('change', () => setFile(input.files[0] || null));
$('removeFile').addEventListener('click', event => {
    event.stopPropagation(); input.value = ''; setFile(null);
});
for (const name of ['dragenter', 'dragover']) dropzone.addEventListener(name, event => {
    event.preventDefault(); dropzone.classList.add('dragging');
});
for (const name of ['dragleave', 'drop']) dropzone.addEventListener(name, event => {
    event.preventDefault(); dropzone.classList.remove('dragging');
});
dropzone.addEventListener('drop', event => setFile(event.dataTransfer.files[0] || null));
policies.forEach(item => item.addEventListener('change', invalidateSession));
$('submitButton').addEventListener('click', tokenize);
$('keyInput').addEventListener('change', () => loadKey($('keyInput').files[0]));
$('reportInput').addEventListener('input', () => { clearRestored(); updateControls(); });
$('restoreButton').addEventListener('click', restore);
$('downloadKey').addEventListener('click', downloadKey);
$('copySanitized').addEventListener('click', () => copyText(sanitizedText, 'result'));
$('copyRestored').addEventListener('click', () => copyText($('restoredReport').textContent, 'restoreStatus'));
$('clearSession').addEventListener('click', () => { input.value = ''; setFile(null); });
updateControls();
