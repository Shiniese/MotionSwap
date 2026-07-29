package ui

import (
	"io"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/storage"
)

func readURI(uri fyne.URI) ([]byte, error) {
	r, err := storage.Reader(uri)
	if err != nil {
		return nil, err
	}
	defer r.Close()
	return io.ReadAll(r)
}

func writeURI(uri fyne.URI, data []byte) error {
	w, err := storage.Writer(uri)
	if err != nil {
		return err
	}
	defer w.Close()
	_, err = w.Write(data)
	return err
}
