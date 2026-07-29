# -------------------------- 项目配置 -------------------------- #
APP_NAME     := MotionSwap
APP_VERSION  := 1.0.0
MAIN_PATH    := .
APP_ID       := com.mingshiwu.motionswap
ICON_PATH    := Icon.png
OUTPUT_DIR   := dist

# 编译公共参数
CGO_ENABLED  := 1
LDFLAGS      := -s -w -X main.version=$(APP_VERSION)
GO_BUILD     := go build -ldflags '$(LDFLAGS)'

# Git提交哈希和编译时间，自动注入
GIT_COMMIT := $(shell git rev-parse --short HEAD 2>/dev/null || echo "unknown")
BUILD_TIME := $(shell date +"%Y-%m-%d %H:%M:%S")


# -------------------------- 编译目标 -------------------------- #
.PHONY: all windows linux android clean release

# 默认编译所有平台
all: windows linux android

# -------------------------- Windows 编译 -------------------------- #
# Windows amd64 (x86_64 主流64位系统)
windows-amd64:
	@mkdir -p $(OUTPUT_DIR)
	GOOS=windows GOARCH=amd64 CGO_ENABLED=$(CGO_ENABLED) CC=x86_64-w64-mingw32-gcc \
	$(GO_BUILD) -o $(OUTPUT_DIR)/$(APP_NAME)_windows_amd64.exe $(MAIN_PATH)

# Windows 386 (x86 32位兼容)
windows-386:
	@mkdir -p $(OUTPUT_DIR)
	GOOS=windows GOARCH=386 CGO_ENABLED=$(CGO_ENABLED) CC=i686-w64-mingw32-gcc \
	$(GO_BUILD) -o $(OUTPUT_DIR)/$(APP_NAME)_windows_386.exe $(MAIN_PATH)

# Windows 全架构编译
windows: windows-amd64 windows-386

# -------------------------- Linux 编译 -------------------------- #
# Linux amd64 (x86_64 主流服务器/桌面)
linux-amd64:
	@mkdir -p $(OUTPUT_DIR)
	GOOS=linux GOARCH=amd64 CGO_ENABLED=$(CGO_ENABLED) CC=gcc \
	$(GO_BUILD) -o $(OUTPUT_DIR)/$(APP_NAME)_linux_amd64 $(MAIN_PATH)

# Linux arm64 (ARM架构服务器/树莓派4+/等设备)
linux-arm64:
	@mkdir -p $(OUTPUT_DIR)
	GOOS=linux GOARCH=arm64 CGO_ENABLED=$(CGO_ENABLED) CC=aarch64-linux-gnu-gcc \
	$(GO_BUILD) -o $(OUTPUT_DIR)/$(APP_NAME)_linux_arm64 $(MAIN_PATH)

# Linux 全架构编译
linux: linux-amd64

# -------------------------- Android 编译 -------------------------- #
# 直接使用Fyne官方打包命令生成APK
android:
	@mkdir -p $(OUTPUT_DIR)
# 1. 先在项目根目录执行fyne package，生成的apk默认在当前目录
	fyne package -os android
# 2. 打包完成后把apk移动到dist目录，同时可以自定义命名
	@mv $(APP_NAME).apk $(OUTPUT_DIR)/$(APP_NAME)_android.apk


# -------------------------- 工具命令 -------------------------- #
# 清理编译产物
clean:
	rm -rf $(OUTPUT_DIR)

# 自动上传到GitHub Release（需要先安装GitHub CLI工具：https://cli.github.com/）
release:
	@echo "正在上传版本 v$(APP_VERSION) 到GitHub Release..."
	gh release create v$(APP_VERSION) \
		--title "$(APP_NAME) v$(APP_VERSION)" \
		--notes "版本 v$(APP_VERSION) 发布，Git提交：$(GIT_COMMIT)，编译时间：$(BUILD_TIME)" \
		$(OUTPUT_DIR)/*
	@echo "上传完成！"