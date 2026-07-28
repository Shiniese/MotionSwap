package ui

import (
	"fyne.io/fyne/v2"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/theme"
)

type App struct {
	window fyne.Window
}

func NewApp(w fyne.Window) *App {
	a := &App{window: w}
	w.SetContent(a.buildUI())
	w.Resize(fyne.NewSize(900, 600))
	return a
}

func (a *App) buildUI() fyne.CanvasObject {
	extractTab := container.NewTabItemWithIcon("提取动态照片", theme.DownloadIcon(), NewExtractTab(a.window).Build())
	mergeTab := container.NewTabItemWithIcon("合并动态照片", theme.UploadIcon(), NewMergeTab(a.window).Build())

	tabs := container.NewAppTabs(extractTab, mergeTab)
	return tabs
}
