package camunda

import (
	"context"
	"fmt"
	"log"
	"sync"
	"time"
)

// Handler executes a job and returns the variables to complete it with.
// Returning an error fails the job (Camunda retries it with backoff and raises
// an incident in Operate once retries are exhausted).
type Handler func(ctx context.Context, job *Job) (map[string]any, error)

// Worker polls the REST API for jobs of the registered types.
type Worker struct {
	client   *Client
	name     string
	handlers map[string]Handler
	// MaxJobs is the number of jobs activated per poll per job type.
	MaxJobs int
	// LockTimeout is how long an activated job is locked to this worker.
	LockTimeout time.Duration
}

func NewWorker(c *Client, name string) *Worker {
	return &Worker{client: c, name: name, handlers: map[string]Handler{}, MaxJobs: 16, LockTimeout: 60 * time.Second}
}

func (w *Worker) Handle(jobType string, h Handler) { w.handlers[jobType] = h }

// Run starts one long-polling loop per job type and blocks until ctx is done.
func (w *Worker) Run(ctx context.Context) {
	var wg sync.WaitGroup
	for t, h := range w.handlers {
		wg.Add(1)
		go func(jobType string, h Handler) {
			defer wg.Done()
			w.poll(ctx, jobType, h)
		}(t, h)
	}
	wg.Wait()
}

func (w *Worker) poll(ctx context.Context, jobType string, h Handler) {
	log.Printf("[worker] subscribed to job type %q", jobType)
	for ctx.Err() == nil {
		jobs, err := w.client.ActivateJobs(ctx, ActivateRequest{
			Type:              jobType,
			Worker:            w.name,
			Timeout:           w.LockTimeout.Milliseconds(),
			MaxJobsToActivate: w.MaxJobs,
			RequestTimeout:    20_000,
		})
		if err != nil {
			if ctx.Err() == nil {
				log.Printf("[worker] activate %s: %v", jobType, err)
				time.Sleep(2 * time.Second)
			}
			continue
		}
		if len(jobs) == 0 {
			// Long-polling returns empty when the request timeout elapses.
			time.Sleep(200 * time.Millisecond)
			continue
		}
		var wg sync.WaitGroup
		for i := range jobs {
			wg.Add(1)
			go func(job *Job) {
				defer wg.Done()
				w.execute(ctx, job, h)
			}(&jobs[i])
		}
		wg.Wait()
	}
}

func (w *Worker) execute(ctx context.Context, job *Job, h Handler) {
	vars, err := safeCall(ctx, job, h)
	if err != nil {
		retries := job.Retries - 1
		if retries < 0 {
			retries = 0
		}
		log.Printf("[worker] job %s (%s/%s) failed, retries left %d: %v", job.JobKey, job.Type, job.ElementID, retries, err)
		if ferr := w.client.FailJob(ctx, job.JobKey, retries, err.Error(), 5*time.Second); ferr != nil {
			log.Printf("[worker] fail job %s: %v", job.JobKey, ferr)
		}
		return
	}
	if cerr := w.client.CompleteJob(ctx, job.JobKey, vars); cerr != nil {
		log.Printf("[worker] complete job %s (%s): %v", job.JobKey, job.Type, cerr)
		return
	}
	log.Printf("[worker] completed %-22s element=%-28s pi=%s", job.Type, job.ElementID, job.ProcessInstanceKey)
}

func safeCall(ctx context.Context, job *Job, h Handler) (vars map[string]any, err error) {
	defer func() {
		if r := recover(); r != nil {
			err = fmt.Errorf("panic: %v", r)
		}
	}()
	return h(ctx, job)
}
