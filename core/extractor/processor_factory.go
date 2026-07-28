package extractor

type ProcessorFactory struct {
	processors []MotionPhotoProcessor
}

func NewProcessorFactory() *ProcessorFactory {
	return &ProcessorFactory{
		processors: []MotionPhotoProcessor{
			&XiaomiProcessor{},
			&AndroidProcessor{},
			&HuaweiProcessor{},
		},
	}
}

func (f *ProcessorFactory) Detect(xmpInfo map[string]string, data []byte) MotionPhotoProcessor {
	if xmpInfo != nil {
		for _, p := range f.processors {
			if p.CanProcess(xmpInfo) {
				return p
			}
		}
	}

	if data != nil {
		huawei := &HuaweiProcessor{}
		if huawei.CanProcessByFTYP(data) {
			return huawei
		}
		unknown := &UnknownProcessor{}
		if unknown.CanProcessByFTYP(data) {
			return unknown
		}
	}

	return nil
}
