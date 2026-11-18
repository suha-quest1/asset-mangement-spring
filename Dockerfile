# Multi-stage build for all Go services.
# --build-arg SERVICE=worker|apiserver|mockses|mockcreditfacility|demodriver
FROM golang:1.24-alpine AS builder
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
ARG SERVICE
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /bin/service ./cmd/${SERVICE}

FROM alpine:3.21
RUN apk add --no-cache ca-certificates tzdata
WORKDIR /app
COPY --from=builder /bin/service /bin/service
COPY bpmn /app/bpmn
ENTRYPOINT ["/bin/service"]
