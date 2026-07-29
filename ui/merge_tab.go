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

	"MotionSwap/core/merger"
)

type mergePair struct {
	imgURI  fyne.URI
	vidURI  fyne.URI
	imgName string
	vidName string
	brand   string
	status  string
}

type MergeTab struct {
	window    fyne.Window
	srcEntry  *widget.Entry
	srcURI    fyne.ListableURI
	dstEntry  *widget.Entry
	dstURI    fyne.ListableURI
	brandSel  *widget.Select
	scanBtn   *widget.Button
	startBtn  *widget.Button
	stopBtn   *widget.Button
	progress  *widget.ProgressBar
	statsLabel *widget.Label
	logEntry  *widget.Entry
	fileList  *widget.List
	pairs     []mergePair
	scanning  atomic.Bool
	running   atomic.Bool
	stopCh    chan struct{}
}

func NewMergeTab(w fyne.Window) *MergeTab {
	return &MergeTab{
		window: w,
		stopCh: make(chan struct{}),
	}
}

func (t *MergeTab) Build() fyne.CanvasObject {
	t.srcEntry = widget.NewEntry()
	t.srcEntry.SetPlaceHolder("选择源目录（包含 JPG+MP4 配对）")
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

	t.brandSel = widget.NewSelect([]string{"自动检测", "Xiaomi", "Android", "Huawei"}, nil)
	t.brandSel.SetSelected("自动检测")

	t.scanBtn = widget.NewButtonWithIcon("扫描文件", theme.SearchIcon(), t.scanDir)
	t.startBtn = widget.NewButtonWithIcon("开始合并", theme.MediaPlayIcon(), t.startMerge)
	t.stopBtn = widget.NewButtonWithIcon("停止", theme.MediaStopIcon(), t.stopMerge)
	t.stopBtn.Disable()

	t.progress = widget.NewProgressBar()
	t.statsLabel = widget.NewLabel("就绪")

	t.logEntry = widget.NewMultiLineEntry()
	t.logEntry.SetMinRowsVisible(5)
	t.logEntry.Disable()

	t.fileList = widget.NewList(
		func() int { return len(t.pairs) },
		func() fyne.CanvasObject {
			return container.NewHBox(
				widget.NewIcon(theme.FileImageIcon()),
				widget.NewLabel("image.jpg"),
				widget.NewLabel(" + "),
				widget.NewLabel("video.mp4"),
				layout.NewSpacer(),
				widget.NewLabel("品牌"),
				layout.NewSpacer(),
				widget.NewLabel("状态"),
			)
		},
		func(id widget.ListItemID, obj fyne.CanvasObject) {
			if id < len(t.pairs) {
				p := t.pairs[id]
				box := obj.(*fyne.Container)
				box.Objects[1].(*widget.Label).SetText(p.imgName)
				box.Objects[3].(*widget.Label).SetText(p.vidName)
				box.Objects[5].(*widget.Label).SetText(p.brand)
				box.Objects[7].(*widget.Label).SetText(p.status)
			}
		},
	)

	dirGrid := container.NewGridWithColumns(3,
		container.NewBorder(nil, nil, widget.NewLabel("源目录:"), srcBrowse, t.srcEntry),
	)
	outGrid := container.NewGridWithColumns(3,
		container.NewBorder(nil, nil, widget.NewLabel("输出目录:"), dstBrowse, t.dstEntry),
	)
	brandRow := container.NewHBox(widget.NewLabel("输出格式:"), t.brandSel)

	btnRow := container.NewHBox(t.scanBtn, layout.NewSpacer(), t.startBtn, t.stopBtn)

	content := container.NewBorder(
		container.NewVBox(
			dirGrid,
			outGrid,
			brandRow,
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

func (t *MergeTab) log(msg string) {
	fyne.Do(func() {
		t.logEntry.SetText(t.logEntry.Text + msg + "\n")
		t.logEntry.CursorRow = len(strings.Split(t.logEntry.Text, "\n")) - 1
	})
}

func (t *MergeTab) getSrcListableURI() fyne.ListableURI {
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
	l, err := storage.ListerForURI(uri)
	if err != nil {
		return nil
	}
	return l
}

func (t *MergeTab) getDstURI() fyne.URI {
	if t.dstURI != nil {
		return t.dstURI
	}
	path := t.dstEntry.Text
	if path == "" {
		return nil
	}
	return storage.NewFileURI(path)
}

func (t *MergeTab) scanDir() {
	if t.scanning.Load() {
		return
	}
	t.scanning.Store(true)
	t.scanBtn.Disable()

	srcURI := t.getSrcListableURI()
	if srcURI == nil {
		dialog.ShowError(fmt.Errorf("请选择源目录"), t.window)
		t.scanning.Store(false)
		t.scanBtn.Enable()
		return
	}

	t.pairs = nil
	t.fileList.Refresh()
	t.statsLabel.SetText("扫描中...")
	t.log("扫描目录: " + srcURI.Path())

	go func() {
		defer func() {
			t.scanning.Store(false)
			fyne.Do(func() { t.scanBtn.Enable() })
		}()

		entries, err := storage.List(srcURI)
		if err != nil {
			fyne.Do(func() {
				dialog.ShowError(fmt.Errorf("读取目录失败: %w", err), t.window)
			})
			return
		}

		jpgMap := make(map[string]fyne.URI)
		for _, entry := range entries {
			name := strings.ToLower(entry.Name())
			if strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".jpeg") {
				base := strings.TrimSuffix(name, filepath.Ext(name))
				jpgMap[base] = entry
			}
		}

		var found []mergePair
		for _, entry := range entries {
			name := strings.ToLower(entry.Name())
			if !strings.HasSuffix(name, ".mp4") && !strings.HasSuffix(name, ".mov") {
				continue
			}
			base := strings.TrimSuffix(name, filepath.Ext(name))
			if imgURI, ok := jpgMap[base]; ok {
				found = append(found, mergePair{
					imgURI:  imgURI,
					vidURI:  entry,
					imgName: imgURI.Name(),
					vidName: entry.Name(),
					brand:   t.brandSel.Selected,
					status:  "待处理",
				})
			}
		}

		fyne.Do(func() {
			t.pairs = found
			t.fileList.Refresh()
			t.statsLabel.SetText(fmt.Sprintf("发现 %d 个配对", len(found)))
		})
		t.log(fmt.Sprintf("扫描完成: 发现 %d 个 JPG+MP4 配对", len(found)))
	}()
}

func (t *MergeTab) startMerge() {
	if t.running.Load() {
		return
	}
	if len(t.pairs) == 0 {
		dialog.ShowInformation("提示", "请先扫描目录", t.window)
		return
	}
	dstURI := t.getDstURI()
	if dstURI == nil {
		dialog.ShowError(fmt.Errorf("请选择输出目录"), t.window)
		return
	}

	brand := resolveBrand(t.brandSel.Selected)

	t.running.Store(true)
	t.startBtn.Disable()
	t.scanBtn.Disable()
	t.stopBtn.Enable()
	t.stopCh = make(chan struct{})

	m := merger.NewMerger()
	total := len(t.pairs)

	go func() {
		success := 0
		fail := 0
		conflictAction := ""

		for i := range t.pairs {
			select {
			case <-t.stopCh:
				t.log("合并已停止")
				t.finishMerge()
				return
			default:
			}

			p := &t.pairs[i]
			p.status = "处理中..."
			fyne.Do(func() {
				t.fileList.Refresh()
			})

			jpegData, err := readURI(p.imgURI)
			if err != nil {
				p.status = "失败: " + err.Error()
				fail++
				t.log(fmt.Sprintf("  ✗ %s + %s: %v", p.imgName, p.vidName, err))
				fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
				continue
			}
			mp4Data, err := readURI(p.vidURI)
			if err != nil {
				p.status = "失败: " + err.Error()
				fail++
				t.log(fmt.Sprintf("  ✗ %s + %s: %v", p.imgName, p.vidName, err))
				fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
				continue
			}

			result, err := m.Merge(jpegData, mp4Data, brand)
			if err != nil {
				p.status = "失败: " + err.Error()
				fail++
				t.log(fmt.Sprintf("  ✗ %s + %s: %v", p.imgName, p.vidName, err))
				fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
				continue
			}

			outURI, skip := t.checkConflict(dstURI, p.imgName, &conflictAction)
			if skip {
				p.status = "跳过"
				fail++
				fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
				continue
			}

			if err := writeURI(outURI, result); err != nil {
				p.status = "失败: 写入错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: %v", p.imgName, err))
				fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
				continue
			}

			p.status = "完成"
			success++
			t.log(fmt.Sprintf("  ✓ %s + %s → %s", p.imgName, p.vidName, p.imgName))
			fyne.Do(func() { t.progress.SetValue(float64(i+1) / float64(total)) })
		}

		t.log(fmt.Sprintf("合并完成: 成功 %d, 失败 %d, 总计 %d", success, fail, total))
		t.finishMerge()
	}()
}

func (t *MergeTab) checkConflict(parent fyne.URI, name string, action *string) (fyne.URI, bool) {
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

func (t *MergeTab) stopMerge() {
	close(t.stopCh)
}

func (t *MergeTab) finishMerge() {
	t.running.Store(false)
	fyne.Do(func() {
		t.startBtn.Enable()
		t.scanBtn.Enable()
		t.stopBtn.Disable()
		t.progress.SetValue(0)
	})
}

func resolveBrand(s string) merger.MotionPhotoBrand {
	switch s {
	case "Xiaomi":
		return merger.BrandXiaomi
	case "Android":
		return merger.BrandAndroid
	case "Huawei":
		return merger.BrandHuawei
	default:
		return merger.BrandAndroid
	}
}
