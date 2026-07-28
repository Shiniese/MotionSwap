package ui

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"

	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/dialog"
	"fyne.io/fyne/v2/layout"
	"fyne.io/fyne/v2/theme"
	"fyne.io/fyne/v2/widget"

	"MotionSwap/core/merger"
)

type mergePair struct {
	imgPath string
	vidPath string
	imgName string
	vidName string
	brand   string
	status  string
}

type MergeTab struct {
	window    fyne.Window
	srcEntry  *widget.Entry
	dstEntry  *widget.Entry
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
				t.srcEntry.SetText(uri.Path())
			}
		}, t.window).Show()
	})
	dstBrowse := widget.NewButtonWithIcon("浏览", theme.FolderOpenIcon(), func() {
		dialog.NewFolderOpen(func(uri fyne.ListableURI, err error) {
			if err == nil && uri != nil {
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
	t.logEntry.SetText(t.logEntry.Text + msg + "\n")
	t.logEntry.CursorRow = len(strings.Split(t.logEntry.Text, "\n")) - 1
}

func (t *MergeTab) scanDir() {
	if t.scanning.Load() {
		return
	}
	t.scanning.Store(true)
	t.scanBtn.Disable()
	defer func() {
		t.scanning.Store(false)
		t.scanBtn.Enable()
	}()

	srcDir := t.srcEntry.Text
	if srcDir == "" {
		dialog.ShowError(fmt.Errorf("请选择源目录"), t.window)
		return
	}

	t.pairs = nil
	t.fileList.Refresh()
	t.log("扫描目录: " + srcDir)

	entries, err := os.ReadDir(srcDir)
	if err != nil {
		dialog.ShowError(fmt.Errorf("读取目录失败: %w", err), t.window)
		return
	}

	jpgMap := make(map[string]string)
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := strings.ToLower(entry.Name())
		if strings.HasSuffix(name, ".jpg") || strings.HasSuffix(name, ".jpeg") {
			base := strings.TrimSuffix(name, filepath.Ext(name))
			jpgMap[base] = filepath.Join(srcDir, entry.Name())
		}
	}

	count := 0
	for _, entry := range entries {
		if entry.IsDir() {
			continue
		}
		name := strings.ToLower(entry.Name())
		if !strings.HasSuffix(name, ".mp4") && !strings.HasSuffix(name, ".mov") {
			continue
		}
		base := strings.TrimSuffix(name, filepath.Ext(name))
		if imgPath, ok := jpgMap[base]; ok {
			t.pairs = append(t.pairs, mergePair{
				imgPath: imgPath,
				vidPath: filepath.Join(srcDir, entry.Name()),
				imgName: filepath.Base(imgPath),
				vidName: entry.Name(),
				brand:   t.brandSel.Selected,
				status:  "待处理",
			})
			count++
		}
	}

	t.fileList.Refresh()
	t.statsLabel.SetText(fmt.Sprintf("发现 %d 个配对", count))
	t.log(fmt.Sprintf("扫描完成: 发现 %d 个 JPG+MP4 配对", count))
}

func (t *MergeTab) startMerge() {
	if t.running.Load() {
		return
	}
	if len(t.pairs) == 0 {
		dialog.ShowInformation("提示", "请先扫描目录", t.window)
		return
	}
	dstDir := t.dstEntry.Text
	if dstDir == "" {
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
			t.fileList.Refresh()

			result, err := m.MergeFiles(p.imgPath, p.vidPath, brand)
			if err != nil {
				p.status = "失败: " + err.Error()
				fail++
				t.log(fmt.Sprintf("  ✗ %s + %s: %v", p.imgName, p.vidName, err))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			outPath := filepath.Join(dstDir, p.imgName)
			outPath, skip := t.checkConflict(outPath, &conflictAction)
			if skip {
				p.status = "跳过"
				fail++
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			if err := os.WriteFile(outPath, result, 0644); err != nil {
				p.status = "失败: 写入错误"
				fail++
				t.log(fmt.Sprintf("  ✗ %s: %v", p.imgName, err))
				t.progress.SetValue(float64(i+1) / float64(total))
				continue
			}

			p.status = "完成"
			success++
			t.log(fmt.Sprintf("  ✓ %s + %s → %s", p.imgName, p.vidName, p.imgName))
			t.progress.SetValue(float64(i+1) / float64(total))
		}

		t.log(fmt.Sprintf("合并完成: 成功 %d, 失败 %d, 总计 %d", success, fail, total))
		t.finishMerge()
	}()
}

func (t *MergeTab) checkConflict(path string, action *string) (string, bool) {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path, false
	}
	if *action == "" {
		*action = "rename"
		dialog.ShowInformation("文件冲突",
			fmt.Sprintf("文件已存在，将自动重命名:\n%s", path), t.window)
	}
	switch *action {
	case "overwrite":
		return path, false
	case "skip":
		return "", true
	default:
		ext := filepath.Ext(path)
		base := strings.TrimSuffix(path, ext)
		return base + "_1" + ext, false
	}
}

func (t *MergeTab) stopMerge() {
	close(t.stopCh)
}

func (t *MergeTab) finishMerge() {
	t.running.Store(false)
	t.startBtn.Enable()
	t.scanBtn.Enable()
	t.stopBtn.Disable()
	t.progress.SetValue(0)
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
