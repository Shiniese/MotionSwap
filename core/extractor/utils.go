package extractor

import "strings"

func extractLastNumber(s string) int {
	last := 0
	for _, part := range strings.Split(s, ",") {
		part = strings.TrimSpace(part)
		n := 0
		for _, c := range part {
			if c >= '0' && c <= '9' {
				n = n*10 + int(c-'0')
			} else {
				break
			}
		}
		if n > 0 {
			last = n
		}
	}
	return last
}

func extractTimestamp(xmpInfo map[string]string, key string) *int64 {
	s, ok := xmpInfo[key]
	if !ok || s == "" {
		return nil
	}
	var v int64
	for _, c := range s {
		if c >= '0' && c <= '9' {
			v = v*10 + int64(c-'0')
		}
	}
	return &v
}
