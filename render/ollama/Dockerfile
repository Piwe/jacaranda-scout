# Local-model runtime for Render: Ollama with the chat model baked into the image (no disk needed).
# A 1.5B model keeps CPU latency and RAM reasonable on a 2 GB instance; 3B+ wants more RAM.
FROM ollama/ollama:latest
ARG MODEL=qwen2.5:1.5b
ENV OLLAMA_HOST=0.0.0.0:11434 OLLAMA_KEEP_ALIVE=24h
RUN ollama serve & pid=$!; sleep 5; ollama pull "$MODEL"; status=$?; kill $pid; exit $status
EXPOSE 11434
