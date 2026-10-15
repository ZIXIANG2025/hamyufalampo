# SafeDocs — local reversible tokenization

SafeDocs extracts PDF text, uses a local context-aware model to identify selected sensitive categories, and replaces detected values with consistent tokens such as `[NAME_001]`, `[IC_001]`, `[MATRIC_001]` and `[GENDER_001]`. Copy the sanitized text into your external AI, then paste its report into SafeDocs to restore identities locally.

The original PDF is not modified. There is no PDF redaction or processed-PDF download feature. The UI's **Encrypt/Decrypt** labels describe reversible tokenization, **not cryptographic encryption**. The JSON key contains the original sensitive values in plain text.

## Run locally

Requirements: JDK 17+, Maven or the included wrapper, and a local model server supporting **`POST /responses` with strict `text.format` JSON schema output**. A chat-completions-only server needs a compatible adapter; no model or adapter is bundled.

macOS/Linux (replace the port and model name with your local server's values):

```sh
export SAFEDOCS_AI_ENABLED=true
export SAFEDOCS_AI_CUSTOM_BASE_URL=http://127.0.0.1:1234/v1
export SAFEDOCS_AI_MODEL=your-local-model
# Optional, only if your local server requires authentication:
# export SAFEDOCS_AI_API_KEY=your-local-server-key
sh ./mvnw spring-boot:run
```

PowerShell:

```powershell
$env:SAFEDOCS_AI_ENABLED = "true"
$env:SAFEDOCS_AI_CUSTOM_BASE_URL = "http://127.0.0.1:1234/v1"
$env:SAFEDOCS_AI_MODEL = "your-local-model"
.\mvnw.cmd spring-boot:run
```

Open `http://127.0.0.1:8080`. Detection is disabled until explicitly enabled and configured. Hosted OpenAI/Gemini provider settings from previous versions are removed. Model requests allow only literal loopback addresses (`127.0.0.1` or `[::1]`), bypass system proxies, and do not follow redirects. Configure the local model server itself to run locally without forwarding requests or logging sensitive text.

## Workflow

1. Choose a text-based PDF, up to 20 MB.
2. Select the categories to tokenize and click **Tokenize & generate safe text (Encrypt)**.
3. Review **Sanitized Text for External AI**, then copy it. Ask your external AI to preserve all tokens exactly, including brackets, type and number.
4. Keep the **Local Key Mapping Dictionary** private. Its preview is collapsed initially; **Download Local Key (JSON)** saves a plain JSON dictionary.
5. Paste the AI report into **3 Decrypt & Restore AI Analysis** and click **Decrypt & Restore Identity**. Restoration uses the dictionary in memory or an optional imported JSON key. Copy the restored report when ready.

Changing the PDF or selected categories clears the previous output, key and report. A new tokenization replaces the current key. Invalid key imports clear the previous key rather than silently using it. **Clear local session** removes the current file selection, text, key and report from application state; it does not erase copies already downloaded or placed on the clipboard. Refreshing/closing the page loses the in-memory key, so download it if needed later.

## Selected categories and token types

| Card | Types |
| --- | --- |
| People & identity | NAME, IC, MATRIC, GENDER, PASSPORT, IDENTIFIER, SIGNATURE |
| Contact & location | PHONE, EMAIL, ADDRESS, LOCATION |
| Financial data | BANK_ACCOUNT, CARD, TAX, FINANCIAL |
| Other sensitive data | DATE_OF_BIRTH, MEDICAL, OTHER |

Category choices constrain both the model instruction and response schema. Only exact verbatim source values are accepted. Repeated identical values use one token within a document. Distinct spellings, spacing or capitalization remain distinct values so restoration preserves the original text exactly. Token numbering restarts per document. If the model classifies the same exact value more than once, its first classification wins.

The engine matches literal values at Unicode word boundaries so `Ann` does not alter `Anna`, or `male` alter `female`. Longer contained spans take precedence. Partially intersecting spans are combined into an `OTHER` token covering their full union, preserving the exact original substring without exposing an overlapping tail. Existing token-shaped strings are reserved to avoid accidental restoration collisions. Restoration is a single pass: original values that themselves look like tokens are never expanded recursively. Unknown tokens are left intact and counted for review.

## Privacy and limits

- PDF extraction and detection run on your machine. Uploaded PDFs are held in memory; accepted multipart uploads do not spool to disk. The application does not persist keys, original text, model results or uploaded PDFs, or include them in application error messages. OS memory/swap and model-server logging are outside these application guarantees.
- The server returns the sanitized text and dictionary once, with `Cache-Control: no-store`. It retains no shared dictionary or HTTP session. The browser keeps the dictionary in a module variable, not cookies, localStorage or sessionStorage.
- Key import, report restoration and JSON downloads run entirely in the browser and make no server/model requests. Restored text is rendered as text, so document/report HTML is not executed.
- AI can miss sensitive values. Unselected categories remain visible. Review output before copying it to an external AI; tokenized text still conveys context and may remain identifying.
- Only extractable PDF text is processed, with a default limit of 100,000 characters. Scans and images require local OCR first. No PDF/image/metadata output is produced or sanitized, and the original document remains sensitive.
- Long documents use overlapping model chunks. Detection quality depends on the local model and the available context. The integration tests use a synthetic local HTTP model fixture, not a real LLM accuracy evaluation.
- Keys are limited to 2 MB / 10,000 entries; reports to 1,000,000 characters. JSON maps exact tokens to nonempty strings. Keep each key with its matching report: unrelated documents can both contain `[NAME_001]`, and a plain dictionary cannot authenticate which report it belongs to.

## API

`POST /api/safedocs/tokenize` accepts multipart `file` and one or more repeated `policies` fields (`PERSONAL`, `CONTACT`, `FINANCIAL`, `SENSITIVE`). It returns JSON:

```json
{
  "sanitizedText": "[NAME_001] has student number [MATRIC_001].",
  "mapping": {"[NAME_001]": "Alice Tan", "[MATRIC_001]": "A12345"},
  "replacementCount": 2
}
```

Errors return a status code and `{"message":"..."}` without document text or model response bodies. The old `POST /api/safedocs/process` endpoint has been removed. There is no decrypt API: restoration stays in the browser.

## Tests

```sh
sh ./mvnw clean verify
node --test src/test/js/*.test.mjs
```

Java tests cover real PDF extraction, category enforcement, local-only requests, invalid model results, error privacy, token consistency, collisions, overlaps and the removed endpoint. JavaScript tests cover imported key validation and exact, nonrecursive restoration. GitHub Actions runs both suites (JDK 17 and Node 22).

## iText licensing

iText's PDF kernel remains for text extraction. iText is distributed under AGPLv3 or a commercial license. Use the license appropriate to your distribution. The PDF cleanup and layout dependencies have been removed.
