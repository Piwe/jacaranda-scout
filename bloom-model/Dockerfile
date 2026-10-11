# TabPFN bloom-chance sidecar.
#   client (default, small image): Prior Labs' hosted TabPFN; set TABPFN_TOKEN at runtime
#   local: open v2 weights baked into the image; needs ~2 GB RAM and several CPUs to be quick
#     docker build --build-arg TABPFN_BACKEND=local -t bloom-model bloom-model/
FROM python:3.12-slim
ARG TABPFN_BACKEND=client
ENV TABPFN_BACKEND=${TABPFN_BACKEND} PYTHONUNBUFFERED=1
WORKDIR /app
COPY requirements.txt requirements-client.txt ./
RUN if [ "$TABPFN_BACKEND" = "local" ]; then \
      pip install --no-cache-dir --index-url https://download.pytorch.org/whl/cpu torch && \
      pip install --no-cache-dir -r requirements.txt; \
    else \
      pip install --no-cache-dir -r requirements-client.txt; \
    fi
COPY *.py train.csv ./
# local: download the weights at build time so the container starts without internet access to Hugging Face
RUN if [ "$TABPFN_BACKEND" = "local" ]; then \
      python -c "import numpy as np, model; model.make_classifier().fit(np.random.rand(20, 9), np.arange(20) % 2)"; \
    fi
EXPOSE 8090
CMD ["sh", "-c", "uvicorn server:app --host 0.0.0.0 --port ${PORT:-8090}"]
