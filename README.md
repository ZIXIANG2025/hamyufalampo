# SafeDocs

SafeDocs extracts text from text-based PDFs, uses context-aware AI detection for the selected sensitive-data categories, and replaces detected spans with consistent reversible tokens. Tokenized and restored PDF downloads retain the uploaded PDF pages, graphics, and layout while replacing the selected text in place. Sanitized text can also be copied to an external AI service.

## Privacy model

- PDF text extraction and token generation run in the SafeDocs Spring Boot process. The PDF is not persisted by the application. The token mapping is saved as readable JSON in `Desktop\\Tester1.txt` and is automatically deleted after successful restoration using that matching key. Older DPAPI-protected mapping files are decrypted and migrated to readable JSON when read.
- AI detection sends extracted document text to the configured model endpoint. A cloud endpoint receives that text; use a trusted private endpoint for sensitive documents.
- The token mapping contains the original sensitive values. It is returned to the local browser and held in page memory, and is stored as readable text in `Desktop\\Tester1.txt`. For layout-preserving PDF output, the browser sends the selected PDF and mapping only to this local SafeDocs service; they are not sent to the AI provider during PDF substitution.
- Token substitution is reversible pseudonymization rather than cryptographic encryption of the PDF contents.
- Scanned/image-only PDFs are rejected because this version does not include an OCR engine.
- Provider request/response bodies and document content are excluded from application logs.

## Requirements

- JDK 17 or later
- Maven (or the included Maven wrapper)
- A configured OpenAI-compatible API or Gemini API when using AI detection

## Run with an OpenAI-compatible endpoint

PowerShell:

```powershell
$env:OPENAI_API_KEY = "your-key"
$env:SAFEDOCS_AI_CUSTOM_BASE_URL = "https://your-private-endpoint/v1"
$env:OPENAI_MODEL = "your-model"
$env:SAFEDOCS_AI_PROVIDER = "custom"
$env:SAFEDOCS_AI_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

The `custom` provider uses an OpenAI Responses API-compatible endpoint. To use OpenAI's hosted endpoint, set `SAFEDOCS_AI_PROVIDER` to `openai` after reviewing its current data-handling terms for your use case.

## Run with Gemini

```powershell
$env:GEMINI_API_KEY = "your-key"
$env:SAFEDOCS_AI_PROVIDER = "gemini"
$env:SAFEDOCS_AI_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

Set `GEMINI_MODEL` and `GEMINI_BASE_URL` to select an available model and endpoint. Keep keys out of source control and logs.

## Workflow

1. Choose a text-based PDF.
2. Select the sensitive categories for context-aware detection.
3. Tokenize the extracted text. The service returns sanitized text and saves a readable JSON mapping to `Desktop\\Tester1.txt`. Downloading the tokenized PDF removes sensitive glyphs from the original page and draws the token at that position, preserving the rest of the page design.
4. Copy the sanitized text to an external AI service if desired.
5. Save the external AI report as a PDF and select it in the restore panel. SafeDocs decrypts the matching local mapping automatically. Downloading the restored report replaces tokens inside that uploaded PDF while preserving its page design, then removes only that document’s mapping.

The dashboard runs at `http://localhost:8080`. PDF substitution uses the local routes `POST /api/safedocs/tokenize-pdf` and `POST /api/safedocs/restore-pdf`; the `mapping` field is submitted only to the local application.

## iText licensing

iText is distributed under AGPLv3 or a commercial license. Review the license terms that apply to your use before distributing or deploying this application.
