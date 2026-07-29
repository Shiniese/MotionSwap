package ui

import (
	"fmt"
	"path/filepath"
	"strings"
	"sync/atomic"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/storage"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"MotionSwap/core/extractor"
	"MotionSwap/pkg/xmp"
)

type fileStatus struct {
	uri    fyne.URI
	name   string
	brand  string
	status string
}

type ExtractTab struct {
	window      fyne.Window
	srcEntry    *widget.Entry
	srcURI      fyne.ListableURI
	dstEntry    *widget.Entry
	dstURI      fyne.ListableURI
	scanBtn     *widget.Button
	startBtn    *widget.Button
	stopBtn     *widget.Button
	progress    *widget.ProgressBar
	statsLabel  *widget.Label
	logEntry    *widget.Entry
	fileList    *widget.List
	files       []fileStatus
	scanning    atomic.Bool
	running     atomic.Bool
	stopCh      chan struct{}
}

func NewExtractTab(w fyne.Window) *ExtractTab {
	return &ExtractTab{
		window: w,
		stopCh: make(chan struct{}),
	}
}

func (t *ExtractTab) Build() fyne.CanvasObject {
	t.srcEntry = widget.NewEntry()
	t.srcEntry.SetPlaceHolder("选择源目录（包含动态照片）")
	t.dstEntry = widget.NewEntry()
	t.dstEntry.SetPlaceHolder("选择输出目录")

	srcBrowse := widget.NewButtonWithIcon("浏览", theme.FolderOpenIcon(), func() {
		dialog.NewFolderOpen(func(uri fyne.ListableURI, err error) {
			if err == nil && uri != nil {
				t.srcURI = uri
				t.srcEntry.SetText(uri.Path())
			}
		}, t.window).Show()
	})
	dstBrowse := widget.NewButtonWithIcon("浏览", theme.FolderOpenIcon(), func() {
		dialog.NewFolderOpen(func(uri fyne.ListableURI, err error) {
			if err == nil && uri != nil {
				t.dstURI = uri
				t.dstEntry.SetText(uri.Path())
			}
		}, t.window).Show()
	})

	t.scanBtn = widget.NewButtonWithIcon("扫描文件", theme.SearchIcon(), t.scanDir)
	t.startBtn = widget.NewButtonWithIcon("开始提取", theme.MediaPlayIcon(), t.startExtract)
	t.stopBtn = widget.NewButtonWithIcon("停止", theme.MediaStopIcon(), t.stopExtract)
	t.stopBtn.Disable()

	t.progress = widget.NewProgressBar()
	t.statsLabel = widget.NewLabel("就绪")

	t.logEntry = widget.NewMultiLineEntry()
	t.logEntry.SetMinRowsVisible(5)
	t.logEntry.Disable()

	t.fileList = widget.NewList(
		func() int { return len(t.files) },
		func() fyne.CanvasObject {
			return container.NewHBox(
				widget.NewIcon(theme.FileIcon()),
				widget.NewLabel("filename.jpg"),
				layout.NewSpacer(),
				widget.NewLabel("品牌"),
				layout.NewSpacer(),
				widget.NewLabel("状态"),
			)
		},
		func(id widget.ListItemID, obj fyne.CanvasObject) {
			if id < len(t.files) {
				f := t.files[id]
				box := obj.(*fyne.Container)
				box.Objects[1].(*widget.Label).SetText(f.name)
				box.Objects[3].(*widget.Label).SetText(f.brand)
				box.Objects[5].(*widget.Label).SetText(f.status)
			}
		},
	)

	dirGrid := container.NewGridWithColumns(3,
		container.NewBorder(nil, nil, widget.NewLabel("源目录:"), srcBrowse, t.srcEntry),
	)
	outGrid := container.NewGridWithColumns(3,
		container.NewBorder(nil, nil, widget.NewLabel("输出目录:"), dstBrowse, t.dstEntry),
	)

	btnRow := container.NewHBox(t.scanBtn, layout.NewSpacer(), t.startBtn, t.stopBtn)

	content := container.NewBorder(
		container.NewVBox(
			dirGrid,
			outGrid,
			btnRow,
			t.progress,
			t.statsLabel,
		),
		container.NewBorder(
			widget.NewLabelWithStyle("处理日志:", fyne.TextAlignLeading, fyne.TextStyle{Bold: true}),
			nil, nil, nil,
			t.logEntry,
		),
		nil, nil,
		t.fileList,
	)

	return content
}

func (t *ExtractTab) log(msg string) {
	t.logEntry.SetText(t.logEntry.Text + msg + "\n")
	t.logEntry.CursorRow = len(strings.Split(t.logEntry.Text, "\n")) - 1
}

func (t *ExtractTab) getSrcListableURI() fyne.ListableURI {
	if t.srcURI != nil {
		return t.srcURI
	}
	path := t.srcEntry.Text
	if path == "" {
		return nil
	}
	uri := storage.NewFileURI(path)
	if l, ok := uri.(fyne.ListableURI); ok {
		return l
	}
	// wrap non-listable URI into listable
	l, err := storage.ListerForURI(uri)
	if err != nil {
		return nil
	}
	return l
}

func (t *ExtractTab) getDstURI() fyne.URI {
	if t.dstURI != nil {
		return t.dstURI
	}
	path := t.dstEntry.Text
	if path == "" {
		return nil
	}
	return storage.NewFileURI(path)
}

func (t *ExtractTab) scanDir() {
	if t.scanning.Load() {
		return
	}
	t.scanning.Store(true)
	t.scanBtn.Disable()
	defer func() {
		t.scanning.Store(false)
		t.scanBtn.Enable()
	}()

	srcURI := t.getSrcListableURI()
	if srcURI == nil {
		dialog.ShowError(fmt.Errorf("请选择源目录"), t.window)
		return
	}

	t.files = nil
	t.fileList.Refresh()
	t.log("扫描目录: " + srcURI.Path())

	entries, err := storage.List(srcURI)
	if err != nil {
		dialog.ShowError(fmt.Errorf("读取目录失败: %w", err), t.window)
		return
	}

	factory := extractor.NewProcessorFactory()
	count := 0

	for _, entry := range entries {
		name := strings.ToLower(entry.Name())
		if !strings.HasSuffix(name, ".jpg") && !strings.HasSuffix(name, ".jpeg") {
			continue
		}

		data, err := readURI(entry)
		if err != nil {
			t.log(fmt.Sprintf("  跳过 %s: %v", entry.Name(), err))
			continue
		}

		xmpInfo, _ := xmp.ExtractXMP(data)
		p := factory.Detect(xmpInfo, data)
		if p == nil {
			continue
		}

		t.files = append(t.files, fileStatus{
			uri:    entry,
			name:   entry.Name(),
			brand:  p.Brand().String(),
			status: "待处理",
		})
		count++
	}

	t.fileList.Refresh()
	t.statsLabel.SetText(fmt.Sprintf("发现 %d 个动态照片", count))
	t.log(fmt.Sprintf("扫描完成: 发现 %d 个动态照片", count))
}

func (t *ExtractTab) startExtract() {
	if t.running.Load() {
		return
	}
	if len(t.files) == 0 {
		dialog.ShowInformation("提示", "请先扫描目录", t.window)
		return
	}
	dstURI := t.getDstURI()
	if dstURI == nil {
		dialog.ShowError(fmt.Errorf("请选择输出目录"), t.window)
		return
	}

	t.running.Store(true)
	t.startBtn.Disable()
	t.scanBtn.Disable()
	t.stopBtn.Enable()
	t.stopCh = make(chan struct{})

	factory := extractor.NewProcessorFactory()
	total := len(t.files)

	go func() {
		success := 0
		fail := 0
		conflictAction := ""

		for i := range t.files {
			select {
			case <-t.stopCh:
				t.log("提取已停止")
				t.finishExtract()
				return
			default:
			}

			f := &t.files[i]
			f.status = "处理中..."
			t.fileList.Refresh()

			data, err := readURI(f.uri)
			if err != nil {
				f.status = "失败: 读取错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: %v", f.name, err))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			xmpInfo, _ := xmp.ExtractXMP(data)
			p := factory.Detect(xmpInfo, data)
			if p == nil {
				f.status = "跳过"
				t.log(fmt.Sprintf("  - %s: 不是动态照片", f.name))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			result, err := p.Process(data, xmpInfo)
			if err != nil || result == nil {
				f.status = "失败: 处理错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: 处理失败", f.name))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			baseName := strings.TrimSuffix(f.name, filepath.Ext(f.name))
			imgName := baseName + ".jpg"
			vidName := baseName + ".mp4"

			imgURI, skip := t.checkConflict(dstURI, imgName, &conflictAction)
			if skip {
				f.status = "跳过"
				fail++
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}
			vidURI, skip := t.checkConflict(dstURI, vidName, &conflictAction)
			if skip {
				f.status = "跳过"
				fail++
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			if err := writeURI(imgURI, result.ImageData); err != nil {
				f.status = "失败: 写入错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: %v", f.name, err))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}
			if err := writeURI(vidURI, result.VideoData); err != nil {
				f.status = "失败: 写入错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: %v", f.name, err))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			f.status = "完成"
			success++
			t.log(fmt.Sprintf("  ✓ %s → %s, %s", f.name, imgName, vidName))
			t.progress.SetValue(float64(i+1) / float64(total))
		}

		t.log(fmt.Sprintf("提取完成: 成功 %d, 失败 %d, 总计 %d", success, fail, total))
		t.finishExtract()
	}()
}

func (t *ExtractTab) checkConflict(parent fyne.URI, name string, action *string) (fyne.URI, bool) {
	uri, err := storage.Child(parent, name)
	if err != nil {
		dialog.ShowError(fmt.Errorf("构造输出路径失败: %w", err), t.window)
		return nil, true
	}
	exists, _ := storage.Exists(uri)
	if !exists {
		return uri, false
	}
	if *action == "" {
		*action = "rename"
		dialog.ShowInformation("文件冲突",
			fmt.Sprintf("文件已存在，将自动重命名:\n%s", name), t.window)
	}
	switch *action {
	case "overwrite":
		return uri, false
	case "skip":
		return nil, true
	default:
		ext := filepath.Ext(name)
		base := strings.TrimSuffix(name, ext)
		uri, err = storage.Child(parent, base+"_1"+ext)
		if err != nil {
			dialog.ShowError(fmt.Errorf("构造输出路径失败: %w", err), t.window)
			return nil, true
		}
		return uri, false
	}
}

func (t *ExtractTab) stopExtract() {
	close(t.stopCh)
}

func (t *ExtractTab) finishExtract() {
	t.running.Store(false)
	t.startBtn.Enable()
	t.scanBtn.Enable()
	t.stopBtn.Disable()
	t.progress.SetValue(0)
}
