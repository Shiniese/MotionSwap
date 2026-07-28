package xmp

import (
	"bytes"
	"errors"
	"fmt"
	"sort"
	"strings"
)

func BuildXMP(fields map[string]string) ([]byte, error) {
	if fields == nil {
		return nil, errors.New("nil fields")
	}
	if len(fields) == 0 {
		return nil, errors.New("empty fields")
	}

	nsSet := make(map[string]string)
	type entry struct {
		prefix string
		local  string
		value  string
	}
	var entries []entry

	prefixes := make(map[string]struct{})

	for key, value := range fields {
		parts := strings.SplitN(key, ":", 2)
		if len(parts) != 2 {
			continue
		}
		prefix := parts[0]
		local := parts[1]

		if _, seen := prefixes[prefix]; !seen {
			prefixes[prefix] = struct{}{}
			nsURI := nsMapForPrefix(prefix)
			if nsURI != "" {
				nsSet[prefix] = nsURI
			}
		}
		entries = append(entries, entry{prefix: prefix, local: local, value: value})
	}

	sort.Slice(entries, func(i, j int) bool {
		if entries[i].prefix != entries[j].prefix {
			return entries[i].prefix < entries[j].prefix
		}
		return entries[i].local < entries[j].local
	})

	var buf bytes.Buffer
	buf.WriteString("<?xpacket begin=\"\ufeff\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
	buf.WriteString("\n")
	buf.WriteString("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">")
	buf.WriteString("\n")
	buf.WriteString("  <rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
	buf.WriteString("\n")
	buf.WriteString("    <rdf:Description rdf:about=\"\"")

	for _, prefix := range sortedKeys(nsSet) {
		uri := nsSet[prefix]
		buf.WriteString("\n      xmlns:" + prefix + "=\"" + uri + "\"")
	}
	buf.WriteString(">\n")

	for _, e := range entries {
		buf.WriteString("      <" + e.prefix + ":" + e.local + ">" + xmlEscape(e.value) + "</" + e.prefix + ":" + e.local + ">\n")
	}

	buf.WriteString("    </rdf:Description>\n")
	buf.WriteString("  </rdf:RDF>\n")
	buf.WriteString("</x:xmpmeta>\n")
	buf.WriteString("<?xpacket end=\"w\"?>")
	buf.WriteString("\n")

	return buf.Bytes(), nil
}

func InjectXMP(jpegData []byte, fields map[string]string, videoLen int) ([]byte, error) {
	if fields == nil {
		return nil, errors.New("nil fields")
	}

	xmpData, err := BuildXMP(fields)
	if err != nil {
		return nil, fmt.Errorf("build XMP: %w", err)
	}

	return appendXMPToJPEG(jpegData, xmpData)
}

func appendXMPToJPEG(jpegData []byte, xmpData []byte) ([]byte, error) {
	if len(jpegData) < 2 || jpegData[0] != 0xFF || jpegData[1] != 0xD8 {
		return nil, errors.New("not a valid JPEG (no SOI marker)")
	}

	app1Content := append([]byte("Exif\x00\x00"), xmpData...)
	app1Len := len(app1Content) + 2
	if app1Len > 65535 {
		return nil, errors.New("XMP data too large for APP1 segment")
	}

	app1 := make([]byte, 0, 4+app1Len)
	app1 = append(app1, 0xFF, 0xE1, byte(app1Len>>8), byte(app1Len&0xFF))
	app1 = append(app1, app1Content...)

	pos := 2
	for pos < len(jpegData)-3 {
		if jpegData[pos] != 0xFF {
			pos++
			continue
		}
		marker := jpegData[pos+1]
		if marker == 0xD9 {
			break
		}
		if marker == 0x00 || marker == 0xFF || marker == 0xD8 {
			pos++
			continue
		}
		if pos+3 >= len(jpegData) {
			break
		}
		segLen := int(jpegData[pos+2])<<8 | int(jpegData[pos+3])
		if segLen < 2 {
			pos += 2
			continue
		}
		segEnd := pos + 2 + segLen
		if segEnd > len(jpegData) {
			break
		}
		if marker == 0xE1 {
			result := make([]byte, 0, len(jpegData)-segLen-2+4+len(app1Content))
			result = append(result, jpegData[:pos]...)
			result = append(result, app1...)
			result = append(result, jpegData[segEnd:]...)
			return result, nil
		}
		pos = segEnd
	}

	result := make([]byte, 0, len(jpegData)+4+len(app1Content))
	result = append(result, jpegData[:2]...)
	result = append(result, app1...)
	result = append(result, jpegData[2:]...)
	return result, nil
}

func nsMapForPrefix(prefix string) string {
	switch prefix {
	case "GCamera":
		return "http://ns.google.com/photos/1.0/camera/"
	case "GContainer":
		return "http://ns.google.com/photos/1.0/container/"
	case "GPhoto":
		return "http://ns.google.com/photos/1.0/photo/"
	default:
		return ""
	}
}

func sortedKeys(m map[string]string) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}

func xmlEscape(s string) string {
	s = strings.ReplaceAll(s, "&", "&amp;")
	s = strings.ReplaceAll(s, "<", "&lt;")
	s = strings.ReplaceAll(s, ">", "&gt;")
	return s
}
