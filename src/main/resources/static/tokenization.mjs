// Pure local utilities, shared with browser-independent regression tests.
export const MAX_KEY_BYTES = 2 * 1024 * 1024;
export const MAX_REPORT_CHARACTERS = 1_000_000;
const TOKEN_KEY = /^\[[A-Z][A-Z0-9_]*_[0-9]{3,}\]$/;
const TOKEN_PATTERN = /\[[A-Z][A-Z0-9_]*_[0-9]{3,}\]/g;

export function validateMapping(candidate) {
    if (!candidate || typeof candidate !== 'object' || Array.isArray(candidate)) {
        throw new Error('The key must be a JSON object mapping tokens to original text.');
    }
    const entries = Object.entries(candidate);
    if (entries.length > 10_000) throw new Error('The key contains too many entries.');
    if (new TextEncoder().encode(JSON.stringify(candidate)).length > MAX_KEY_BYTES) {
        throw new Error('The key exceeds the 2 MB limit.');
    }
    const mapping = Object.create(null);
    for (const [token, value] of entries) {
        if (token.length > 100 || !TOKEN_KEY.test(token) || typeof value !== 'string' || !value.trim()) {
            throw new Error('Every key entry must use a token such as [NAME_001] and a non-empty text value.');
        }
        mapping[token] = value;
    }
    return Object.freeze(mapping);
}

export function restoreReport(report, mapping) {
    if (typeof report !== 'string' || !report.trim()) throw new Error('Paste an AI report first.');
    if (report.length > MAX_REPORT_CHARACTERS) throw new Error('The report exceeds 1,000,000 characters.');
    if (!mapping) throw new Error('Tokenize a PDF or load a local JSON key first.');
    let restoredCount = 0;
    const unknown = new Set();
    // A single callback replacement prevents cascading replacements and preserves $, backslashes and HTML literally.
    const text = report.replace(TOKEN_PATTERN, token => {
        if (Object.hasOwn(mapping, token)) {
            restoredCount++;
            return mapping[token];
        }
        unknown.add(token);
        return token;
    });
    return { text, restoredCount, unknownTokens: [...unknown] };
}
