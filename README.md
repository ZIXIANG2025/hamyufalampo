# SafeDocs

**AI Secured: Innovation Without Exposure. Powerful AI. Protected Data.**

SafeDocs identifies personal and sensitive information in PDF text using contextual AI, removes it from the PDF content stream, then performs a second AI scan before returning the result. Detection does not depend on regular expressions or fixed identity formats. AI predictions are accepted only when the returned text is an exact verbatim span from the original document; PDF text geometry is then used to select the irreversible cleanup regions.

## Privacy model

- PDF extraction, positional mapping, redaction, metadata cleanup and output verification run in the SafeDocs Spring Boot process.
- AI analysis sends extracted text to the configured model endpoint. This default cloud configuration is **not** local-only or end-to-end private; do not use sensitive documents with a provider unless its terms and retention controls meet your requirements.
- For a private deployment, configure `SAFEDOCS_AI_CUSTOM_BASE_URL` to an OpenAI Responses API-compatible model endpoint operated within your trusted environment, and provision its API key only through an environment variable. The service intentionally does not persist uploaded files, document text, model responses, or API keys.
- Provider request/response bodies and document content are excluded from application error messages. AI detection is disabled unless explicitly enabled.
- Scanned/image-only PDFs are rejected because this version does not yet include a local OCR engine. PDF text extraction cannot detect information present only in page images.
- The independent output scan uses the configured AI service again. If a scan request fails, the output file is not delivered.

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

The `custom` provider uses the OpenAI Responses API and the configured model. To use OpenAI's hosted endpoint, set `SAFEDOCS_AI_PROVIDER` to `openai` after reviewing the provider's current data-handling terms for your use case.

## Run with Gemini

```powershell
$env:GEMINI_API_KEY = "your-key"
$env:SAFEDOCS_AI_PROVIDER = "gemini"
$env:SAFEDOCS_AI_ENABLED = "true"
.\mvnw.cmd spring-boot:run
```

Set `GEMINI_MODEL` and `GEMINI_BASE_URL` to select an available model and endpoint. Keep keys out of source control and logs.

The dashboard runs at `http://localhost:8080`. Select one or more semantic categories, upload a text-based PDF (maximum 20 MB), and download the output after the second scan succeeds.

## API

`POST /api/safedocs/process` accepts a multipart PDF `file` and one or more `policies` values:

- `PERSONAL`
- `CONTACT`
- `FINANCIAL`
- `SENSITIVE`

The selected categories communicate user intent; the AI scans the document for all supported personal and sensitive entity types, which are then redacted from the extracted PDF text.

```shell
curl.exe -X POST http://localhost:8080/api/safedocs/process `
  -F "file=@input.pdf" `
  -F "policies=PERSONAL" `
  -F "policies=FINANCIAL" `
  --output Secure_input.pdf
```

## iText licensing

iText is distributed under AGPLv3 or a commercial license. Review the license terms that apply to your use before distributing or deploying this application.
