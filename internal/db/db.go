package db

import (
	"context"
	"log"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"
)

// Connect opens a pool and waits (up to ~2 min) for Postgres to accept connections.
func Connect(ctx context.Context, url string) (*pgxpool.Pool, error) {
	pool, err := pgxpool.New(ctx, url)
	if err != nil {
		return nil, err
	}
	for i := 0; i < 40; i++ {
		if err = pool.Ping(ctx); err == nil {
			return pool, nil
		}
		log.Printf("waiting for postgres: %v", err)
		time.Sleep(3 * time.Second)
	}
	return nil, err
}
