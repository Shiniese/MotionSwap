package xmp

import (
	"bytes"
	"encoding/xml"
	"errors"
	"fmt"
)

var xmpPacketEnd = []byte("<?xpacket end=")

func ExtractXMP(data []byte) (map[string]string, error) {
	if data == nil {
		return nil, errors.New("nil data")
	}
	if len(data) < 4 {
		return nil, errors.New("data too short")
	}

	pos := 0
	for pos < len(data)-1 {
		if data[pos] != 0xFF {
			pos++
			continue
		}
		if data[pos+1] != 0xE1 {
			pos += 2
			continue
		}
		if pos+3 >= len(data) {
			return nil, errors.New("truncated APP1 marker")
		}
		segLen := int(data[pos+2])<<8 | int(data[pos+3])
		if segLen < 6 {
			return nil, errors.New("invalid APP1 segment length")
		}
		end := pos + 2 + segLen
		if end > len(data) {
			return nil, errors.New("APP1 segment exceeds file size")
		}
		segData := data[pos+2 : end]
		exifHeader := []byte("Exif\x00\x00")
		contentStart := bytes.Index(segData, exifHeader)
		if contentStart < 0 {
			pos = end
			continue
		}
		xmpData := segData[contentStart+len(exifHeader):]
		result, err := ParseXMPXML(xmpData)
		if err != nil {
			return nil, fmt.Errorf("parse XMP: %w", err)
		}
		return result, nil
	}
	return nil, nil
}

func ParseXMPXML(xmlData []byte) (map[string]string, error) {
	if xmlData == nil {
		return nil, errors.New("nil XML data")
	}

	reader := bytes.NewReader(xmlData)
	decoder := xml.NewDecoder(reader)

	nsMap := make(map[string]string)
	result := make(map[string]string)

	inDescription := false
	depth := 0

	for {
		tok, err := decoder.Token()
		if err != nil {
			break
		}

		switch t := tok.(type) {
		case xml.StartElement:
			depth++
			for _, attr := range t.Attr {
				if attr.Name.Space == "xmlns" {
					nsMap[attr.Value] = attr.Name.Local
				} else if attr.Name.Space == "" && attr.Name.Local == "xmlns" {
					nsMap[attr.Value] = ""
				}
			}
			if t.Name.Local == "Description" {
				inDescription = true
				for _, attr := range t.Attr {
					if attr.Name.Space != "xmlns" && attr.Name.Local != "about" {
						if attr.Name.Space == "" && attr.Name.Local == "xmlns" {
							continue
						}
						key := buildKey(attr.Name, nsMap)
						result[key] = attr.Value
					}
				}
			} else if inDescription {
				var value string
				if err := decoder.DecodeElement(&value, &t); err != nil {
					continue
				}
				key := buildKey(t.Name, nsMap)
				result[key] = value
			}
		case xml.EndElement:
			depth--
			if t.Name.Local == "Description" {
				inDescription = false
			}
		}
	}

	return result, nil
}

func buildKey(name xml.Name, nsMap map[string]string) string {
	if name.Space == "" {
		return name.Local
	}
	if prefix, ok := nsMap[name.Space]; ok {
		if prefix == "" {
			return name.Local
		}
		return prefix + ":" + name.Local
	}
	return name.Local
}
