package mp4

import (
	"bytes"
	"errors"
)

type FTYPBoxInfo struct {
	Offset     int
	BoxSize    int
	MajorBrand string
}

func FindFTYPBox(data []byte) (*FTYPBoxInfo, error) {
	if data == nil {
		return nil, errors.New("nil data")
	}
	if len(data) < 8 {
		return nil, nil
	}

	ftypSig := []byte("ftyp")
	pos := 0
	for pos <= len(data)-8 {
		idx := bytes.Index(data[pos:], ftypSig)
		if idx < 0 {
			return nil, nil
		}
		ftypStart := pos + idx
		if ftypStart < 4 {
			pos = ftypStart + 4
			continue
		}
		boxSizeStart := ftypStart - 4
		boxSize := int(data[boxSizeStart])<<24 | int(data[boxSizeStart+1])<<16 |
			int(data[boxSizeStart+2])<<8 | int(data[boxSizeStart+3])
		if boxSize < 16 || boxSize > len(data)-boxSizeStart {
			pos = ftypStart + 4
			continue
		}
		if boxSizeStart+boxSize > len(data) {
			pos = ftypStart + 4
			continue
		}
		majorBrandStart := ftypStart + 4
		majorBrandEnd := majorBrandStart + 4
		if majorBrandEnd > len(data) {
			return nil, nil
		}
		majorBrand := string(data[majorBrandStart:majorBrandEnd])

		return &FTYPBoxInfo{
			Offset:     boxSizeStart,
			BoxSize:    boxSize,
			MajorBrand: majorBrand,
		}, nil
	}
	return nil, nil
}

func IsMP4(data []byte) bool {
	info, err := FindFTYPBox(data)
	return err == nil && info != nil
}
