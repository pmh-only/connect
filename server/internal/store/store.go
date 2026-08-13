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
	mu       sync.RWMutex
	file     *os.File
	latest   map[string]model.Collection
	events   []Event
	watchers map[chan struct{}]struct{}
}

type Event struct {
	Sequence   uint64
	Collection model.Collection
	Created    bool
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
	store := &Store{
		file: file, latest: make(map[string]model.Collection), watchers: make(map[chan struct{}]struct{}),
	}
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
		s.events = append(s.events, Event{
			Sequence: uint64(len(s.events) + 1), Collection: collection, Created: !exists,
		})
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
	s.mu.Lock()
	defer s.mu.Unlock()
	collection.ReceivedAt = time.Now().UnixMilli()
	if len(s.events) > 0 && collection.ReceivedAt <= s.events[len(s.events)-1].Collection.ReceivedAt {
		collection.ReceivedAt = s.events[len(s.events)-1].Collection.ReceivedAt + 1
	}
	encoded, err := json.Marshal(collection)
	if err != nil {
		return model.Collection{}, fmt.Errorf("encode collection: %w", err)
	}
	encoded = append(encoded, '\n')

	if _, err := s.file.Write(encoded); err != nil {
		return model.Collection{}, fmt.Errorf("append collection: %w", err)
	}
	if err := s.file.Sync(); err != nil {
		return model.Collection{}, fmt.Errorf("sync collection: %w", err)
	}
	_, existed := s.latest[collection.DeviceID]
	s.latest[collection.DeviceID] = collection
	s.events = append(s.events, Event{
		Sequence: uint64(len(s.events) + 1), Collection: collection, Created: !existed,
	})
	for watcher := range s.watchers {
		select {
		case watcher <- struct{}{}:
		default:
		}
	}
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

func (s *Store) Snapshot() ([]model.Collection, uint64) {
	s.mu.RLock()
	boundary := uint64(len(s.events))
	collections, _ := s.snapshotAtLocked(boundary)
	s.mu.RUnlock()
	sort.Slice(collections, func(i, j int) bool {
		return collections[i].DeviceID < collections[j].DeviceID
	})
	return collections, boundary
}

func (s *Store) SnapshotAt(boundary uint64) ([]model.Collection, bool) {
	s.mu.RLock()
	collections, ok := s.snapshotAtLocked(boundary)
	s.mu.RUnlock()
	if !ok {
		return nil, false
	}
	sort.Slice(collections, func(i, j int) bool {
		return collections[i].DeviceID < collections[j].DeviceID
	})
	return collections, true
}

func (s *Store) snapshotAtLocked(boundary uint64) ([]model.Collection, bool) {
	if boundary > uint64(len(s.events)) {
		return nil, false
	}
	latest := make(map[string]model.Collection)
	for _, event := range s.events[:boundary] {
		current, exists := latest[event.Collection.DeviceID]
		if !exists || event.Collection.ReceivedAt >= current.ReceivedAt {
			latest[event.Collection.DeviceID] = event.Collection
		}
	}
	collections := make([]model.Collection, 0, len(latest))
	for _, collection := range latest {
		collections = append(collections, collection)
	}
	return collections, true
}

func (s *Store) EventsAfter(sequence uint64) ([]Event, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	if sequence > uint64(len(s.events)) {
		return nil, false
	}
	events := append([]Event(nil), s.events[sequence:]...)
	return events, true
}

func (s *Store) Subscribe() (<-chan struct{}, func()) {
	watcher := make(chan struct{}, 1)
	s.mu.Lock()
	s.watchers[watcher] = struct{}{}
	s.mu.Unlock()
	return watcher, func() {
		s.mu.Lock()
		delete(s.watchers, watcher)
		s.mu.Unlock()
	}
}

func (s *Store) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.file.Close()
}
