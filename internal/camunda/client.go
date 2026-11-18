// Package camunda is a small, dependency-free Go client for the Camunda 8
// Orchestration Cluster REST API (/v2). It covers exactly what this demo needs:
// deployments, process instance creation, message publication/correlation and
// job activation/completion (used by the job workers in worker.go).
package camunda

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"mime/multipart"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Key is a Camunda entity key. The v2 API returns keys as strings, older
// versions as numbers — accept both.
type Key string

func (k *Key) UnmarshalJSON(b []byte) error {
	s := strings.Trim(string(b), `"`)
	if s == "null" {
		s = ""
	}
	*k = Key(s)
	return nil
}

// APIError is returned for any non-2xx response.
type APIError struct {
	Status int
	Body   string
}

func (e *APIError) Error() string { return fmt.Sprintf("camunda api: HTTP %d: %s", e.Status, e.Body) }

// IsNotFound reports whether err is an HTTP 404 from the API (e.g. no message
// subscription to correlate with).
func IsNotFound(err error) bool {
	var ae *APIError
	return errors.As(err, &ae) && ae.Status == http.StatusNotFound
}

// Client talks to the Camunda REST API.
type Client struct {
	BaseURL  string // e.g. http://localhost:8080
	Username string // optional basic auth (only needed when the API is protected)
	Password string
	HTTP     *http.Client
}

func New(baseURL string) *Client {
	return &Client{
		BaseURL:  strings.TrimRight(baseURL, "/"),
		Username: os.Getenv("CAMUNDA_USERNAME"),
		Password: os.Getenv("CAMUNDA_PASSWORD"),
		HTTP:     &http.Client{Timeout: 60 * time.Second},
	}
}

func (c *Client) newReq(ctx context.Context, method, path string, body io.Reader) (*http.Request, error) {
	req, err := http.NewRequestWithContext(ctx, method, c.BaseURL+path, body)
	if err != nil {
		return nil, err
	}
	if c.Username != "" {
		req.SetBasicAuth(c.Username, c.Password)
	}
	req.Header.Set("Accept", "application/json")
	return req, nil
}

func (c *Client) send(req *http.Request, out any) error {
	resp, err := c.HTTP.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	data, _ := io.ReadAll(resp.Body)
	if resp.StatusCode < 200 || resp.StatusCode > 299 {
		return &APIError{Status: resp.StatusCode, Body: string(data)}
	}
	if out != nil && len(data) > 0 {
		return json.Unmarshal(data, out)
	}
	return nil
}

func (c *Client) doJSON(ctx context.Context, method, path string, in, out any) error {
	var body io.Reader
	if in != nil {
		b, err := json.Marshal(in)
		if err != nil {
			return err
		}
		body = bytes.NewReader(b)
	}
	req, err := c.newReq(ctx, method, path, body)
	if err != nil {
		return err
	}
	if in != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	return c.send(req, out)
}

// Topology is used as a readiness probe.
func (c *Client) Topology(ctx context.Context) (map[string]any, error) {
	var out map[string]any
	err := c.doJSON(ctx, http.MethodGet, "/v2/topology", nil, &out)
	return out, err
}

// WaitReady blocks until the cluster answers the topology endpoint.
func (c *Client) WaitReady(ctx context.Context) error {
	for {
		if _, err := c.Topology(ctx); err == nil {
			return nil
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(3 * time.Second):
		}
	}
}

// Deploy uploads BPMN / DMN / form files in a single deployment.
func (c *Client) Deploy(ctx context.Context, files ...string) (map[string]any, error) {
	var buf bytes.Buffer
	mw := multipart.NewWriter(&buf)
	for _, f := range files {
		data, err := os.ReadFile(f)
		if err != nil {
			return nil, err
		}
		part, err := mw.CreateFormFile("resources", filepath.Base(f))
		if err != nil {
			return nil, err
		}
		if _, err := part.Write(data); err != nil {
			return nil, err
		}
	}
	if err := mw.Close(); err != nil {
		return nil, err
	}
	req, err := c.newReq(ctx, http.MethodPost, "/v2/deployments", &buf)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Content-Type", mw.FormDataContentType())
	var out map[string]any
	return out, c.send(req, &out)
}

type CreateInstanceResponse struct {
	ProcessInstanceKey   Key    `json:"processInstanceKey"`
	ProcessDefinitionKey Key    `json:"processDefinitionKey"`
	ProcessDefinitionID  string `json:"processDefinitionId"`
	Version              int    `json:"processDefinitionVersion"`
}

// CreateProcessInstance starts the latest version of a process definition.
func (c *Client) CreateProcessInstance(ctx context.Context, processID string, vars any) (*CreateInstanceResponse, error) {
	var out CreateInstanceResponse
	err := c.doJSON(ctx, http.MethodPost, "/v2/process-instances", map[string]any{
		"processDefinitionId": processID,
		"variables":           vars,
	}, &out)
	return &out, err
}

// PublishMessage publishes a (buffered) message. messageID makes it idempotent.
func (c *Client) PublishMessage(ctx context.Context, name, correlationKey, messageID string, ttl time.Duration, vars any) error {
	body := map[string]any{
		"name":           name,
		"correlationKey": correlationKey,
		"timeToLive":     ttl.Milliseconds(),
		"variables":      vars,
	}
	if messageID != "" {
		body["messageId"] = messageID
	}
	return c.doJSON(ctx, http.MethodPost, "/v2/messages/publication", body, nil)
}

// CorrelateMessage correlates a message to an *existing* subscription and
// fails with 404 if nothing is waiting for it.
func (c *Client) CorrelateMessage(ctx context.Context, name, correlationKey string, vars any) (map[string]any, error) {
	var out map[string]any
	err := c.doJSON(ctx, http.MethodPost, "/v2/messages/correlation", map[string]any{
		"name":           name,
		"correlationKey": correlationKey,
		"variables":      vars,
	}, &out)
	return out, err
}

// Job is an activated job.
type Job struct {
	JobKey              Key             `json:"jobKey"`
	Type                string          `json:"type"`
	ProcessInstanceKey  Key             `json:"processInstanceKey"`
	ProcessDefinitionID string          `json:"processDefinitionId"`
	ElementID           string          `json:"elementId"`
	Retries             int             `json:"retries"`
	CustomHeaders       map[string]any  `json:"customHeaders"`
	Variables           json.RawMessage `json:"variables"`
}

// Bind decodes the job variables into v.
func (j *Job) Bind(v any) error {
	if len(j.Variables) == 0 {
		return nil
	}
	return json.Unmarshal(j.Variables, v)
}

// Header returns a custom header as string.
func (j *Job) Header(name string) string {
	if v, ok := j.CustomHeaders[name]; ok {
		return fmt.Sprint(v)
	}
	return ""
}

type ActivateRequest struct {
	Type              string `json:"type"`
	Worker            string `json:"worker"`
	Timeout           int64  `json:"timeout"` // job lock, ms
	MaxJobsToActivate int    `json:"maxJobsToActivate"`
	RequestTimeout    int64  `json:"requestTimeout"` // long-poll, ms
}

func (c *Client) ActivateJobs(ctx context.Context, r ActivateRequest) ([]Job, error) {
	var out struct {
		Jobs []Job `json:"jobs"`
	}
	err := c.doJSON(ctx, http.MethodPost, "/v2/jobs/activation", r, &out)
	return out.Jobs, err
}

func (c *Client) CompleteJob(ctx context.Context, key Key, vars any) error {
	body := map[string]any{}
	if vars != nil {
		body["variables"] = vars
	}
	return c.doJSON(ctx, http.MethodPost, "/v2/jobs/"+string(key)+"/completion", body, nil)
}

func (c *Client) FailJob(ctx context.Context, key Key, retries int, msg string, backoff time.Duration) error {
	return c.doJSON(ctx, http.MethodPost, "/v2/jobs/"+string(key)+"/failure", map[string]any{
		"retries":      retries,
		"errorMessage": msg,
		"retryBackOff": backoff.Milliseconds(),
	}, nil)
}
