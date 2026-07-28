package main

import (
	"MotionSwap/ui"

	"fyne.io/fyne/v2/app"
)

func main() {
	a := app.New()
	w := a.NewWindow("MotionSwap - 动态照片转换工具")

	ui.NewApp(w)

	w.ShowAndRun()
}
