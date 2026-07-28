package extractor

type MotionPhotoBrand int

const (
	BrandXiaomi  MotionPhotoBrand = iota + 1
	BrandAndroid
	BrandHuawei
	BrandUnknown
)

func (b MotionPhotoBrand) String() string {
	switch b {
	case BrandXiaomi:
		return "Xiaomi"
	case BrandAndroid:
		return "Android"
	case BrandHuawei:
		return "Huawei"
	case BrandUnknown:
		return "Unknown"
	default:
		return "Unknown"
	}
}

type ProcessResult struct {
	ImageData              []byte
	VideoData              []byte
	PresentationTimestampUs *int64
	VideoOffset            int
}

type MotionPhotoProcessor interface {
	Brand() MotionPhotoBrand
	CanProcess(xmpInfo map[string]string) bool
	Process(data []byte, xmpInfo map[string]string) (*ProcessResult, error)
}
