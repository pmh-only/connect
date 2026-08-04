package store

import (
	"bufio"
	"encoding/json"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"connect/server/internal/model"
)

const maxStoredLineBytes = 8 << 20

type Store struct {
	mu     sync.RWMutex
	file   *os.File
	latest map[string]model.Collection
}

func Open(path string) (*Store, error) {
	if path == "" {
		return nil, errors.New("data file path is required")
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o700); err != nil {
		return nil, fmt.Errorf("create data directory: %w", err)
	}

	file, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR|os.O_APPEND, 0o600)
	if err != nil {
		return nil, fmt.Errorf("open data file: %w", err)
	}
	store := &Store{file: file, latest: make(map[string]model.Collection)}
	if err := store.load(); err != nil {
		file.Close()
		return nil, err
	}
	return store, nil
}

func (s *Store) load() error {
	if _, err := s.file.Seek(0, 0); err != nil {
		return fmt.Errorf("seek data file: %w", err)
	}
	scanner := bufio.NewScanner(s.file)
	scanner.Buffer(make([]byte, 64*1024), maxStoredLineBytes)
	line := 0
	for scanner.Scan() {
		line++
		var collection model.Collection
		if err := json.Unmarshal(scanner.Bytes(), &collection); err != nil {
			return fmt.Errorf("decode data file line %d: %w", line, err)
		}
		if collection.DeviceID == "" {
			continue
		}
		current, exists := s.latest[collection.DeviceID]
		if !exists || collection.ReceivedAt >= current.ReceivedAt {
			s.latest[collection.DeviceID] = collection
		}
	}
	if err := scanner.Err(); err != nil {
		return fmt.Errorf("scan data file: %w", err)
	}
	_, err := s.file.Seek(0, 2)
	return err
}

func (s *Store) Add(collection model.Collection) (model.Collection, error) {
	collection.ReceivedAt = time.Now().UnixMilli()
	encoded, err := json.Marshal(collection)
	if err != nil {
		return model.Collection{}, fmt.Errorf("encode collection: %w", err)
	}
	encoded = append(encoded, '\n')

	s.mu.Lock()
	defer s.mu.Unlock()
	if _, err := s.file.Write(encoded); err != nil {
		return model.Collection{}, fmt.Errorf("append collection: %w", err)
	}
	if err := s.file.Sync(); err != nil {
		return model.Collection{}, fmt.Errorf("sync collection: %w", err)
	}
	s.latest[collection.DeviceID] = collection
	return collection, nil
}

func (s *Store) Latest(deviceID string) (model.Collection, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	collection, ok := s.latest[deviceID]
	return collection, ok
}

func (s *Store) List() []model.Collection {
	s.mu.RLock()
	collections := make([]model.Collection, 0, len(s.latest))
	for _, collection := range s.latest {
		collections = append(collections, collection)
	}
	s.mu.RUnlock()

	sort.Slice(collections, func(i, j int) bool {
		return collections[i].ReceivedAt > collections[j].ReceivedAt
	})
	return collections
}

func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.file.Close()
}
