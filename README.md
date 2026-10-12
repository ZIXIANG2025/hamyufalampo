# hamyufalampo

hamyufalampou

## SafeDocsApplication

Spring Boot service for removing selected Malaysian identity numbers, phone numbers, and email addresses from PDF files.

## Requirements

- JDK 17 or later
- Internet access on the first build so Maven can download dependencies

## Run

From this directory, run:

```shell
.\mvnw.cmd spring-boot:run
```

The service starts at `http://localhost:8080`. Open that address for the upload dashboard.

## API

The dashboard sends a multipart `POST` request to `/api/safedocs/process`. You can also call the API directly with:

- `file`: a PDF file (up to 20 MB)
- `policies`: one or more values from `MYKAD`, `PHONE`, and `EMAIL`

Example:

```shell
curl.exe -X POST http://localhost:8080/api/safedocs/process -F "file=@input.pdf" -F "policies=MYKAD" -F "policies=PHONE" --output Secure_input.pdf
```

Opening `/api/safedocs/process` directly in a browser sends a `GET` request and returns `405 Method Not Allowed`; use the dashboard or the multipart `POST` request above instead.

## iText licensing

iText is distributed under AGPLv3 or a commercial license. Review the license terms that apply to your use before distributing or deploying this application.
