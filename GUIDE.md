# User Guide

Avatar Resizer crops and resizes images to 128×128 pixels. It detects faces automatically and crops around them; if no face is found it crops from the center. Animated GIFs are processed frame-by-frame and returned as animated GIFs. All other image formats are returned as PNG.

---

## Requirements

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) (Windows, Mac, or Linux)

That is the only requirement. Java, Node.js, and Redis are all handled inside Docker.

---

## Starting the application

**1. Get the code**

```bash
git clone https://github.com/KristerJLawlor/Background-Job-System.git
cd Background-Job-System
```

Or download and extract the ZIP from GitHub.

**2. Start**

```bash
docker compose up -d --build
```

The first run downloads base images and compiles the code — this takes a few minutes. Subsequent starts are much faster.

**3. Open the interface**

Once the output shows all services started, open your browser to:

```
http://localhost:8080
```

---

## Using the web interface

### Upload Files tab

1. Drag image files onto the drop zone, or click it to open a file picker
2. Select one or more images (.png, .jpg, .gif — up to 10 MB each)
3. Selected files appear in a list below the drop zone; click ✕ to remove any
4. Click **Process Files**
5. Each file appears as a job card showing its current status

### Enter URLs tab

1. Paste one image URL per line into the text box
2. Click **Process URLs**
3. Each URL appears as a job card

### Job cards

Each card shows the file name or URL and a status badge:

| Badge | Meaning |
|-------|---------|
| Submitting… | Being sent to the server |
| Pending | Queued, waiting for a worker |
| Processing… | Being cropped and resized |
| Done | Finished — Download button appears |
| Failed | Something went wrong (bad URL, unreadable file, etc.) |

When a job shows **Done**, a green **Download** button appears. Click it to save the result. The download is one-shot — the file is deleted from the server after you download it, so save it before refreshing.

### API key

By default the API key is `changeme`. If you changed it in your `.env` file, click the ⚙ icon in the top-right corner and enter your key. It is saved in your browser and remembered across sessions.

---

## Configuration

Copy the example environment file before making changes:

```bash
cp .env.example .env
```

Open `.env` in any text editor. The most important setting:

```
API_KEY=changeme
```

Change this to something secret before exposing the service to the internet. After editing `.env`, restart:

```bash
docker compose up -d
```

For a full list of options see `.env.example`.

---

## Stopping and restarting

```bash
# Stop all services (data is preserved)
docker compose down

# Start again later (no rebuild needed)
docker compose up -d

# Stop and delete all stored data
docker compose down -v
```

---

## After making code changes

```bash
docker compose up -d --build
```

The `--build` flag rebuilds the images. Leave it off if you only changed `.env` or config files.

---

## Troubleshooting

**The page at localhost:8080 doesn't load**

The API container may still be starting. Wait 20–30 seconds and refresh. Check that Docker Desktop is running.

**A job stays on Pending for a long time**

The worker container may have failed to start. Check its logs:

```bash
docker compose logs worker
```

**A job shows Failed**

- For URL jobs: the URL may be unreachable, return a non-image file, or point to a private/internal IP address (blocked for security).
- For file uploads: the file may be corrupt or an unsupported format.

**Jobs disappear after a while**

Completed results expire after 1 hour in Redis and 1 day in S3. This is normal.

**Port 8080 is already in use**

Set a different port in `.env`:

```
PORT=9090
```

Then restart with `docker compose up -d`.

---

## Observability (optional)

These extra interfaces are running alongside the main application:

| URL | What it shows |
|-----|--------------|
| http://localhost:3000 | Grafana — job throughput and processing time graphs (login: admin / admin) |
| http://localhost:16686 | Jaeger — detailed trace for every job |