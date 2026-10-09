#!/usr/bin/env bash
# 由根目录 Icon.png（632x632）生成 adaptive icon 两层与 legacy 位图。
#
# 缩放依据（实测，非估计）：
#   内容 bbox 620x445 @ +7+101，最大半径 = 0.5298 x 内容宽度
#   安全区直径 66/108 => 半径 33dp
#   => 内容宽度 <= 33/0.5298 = 62.3dp
#   => 整图（含奶油边距）<= 62.3 x 632/620 = 63.5dp，即画布的 58.8%
#
# 图标自带背景 #F7F7F4，背景层取同一色，缩放后的前景与背景环无缝相接。
set -euo pipefail

SRC=Icon.png
RES=app/src/main/res
BG='#F7F7F4'

# adaptive: 画布 108dp；legacy: 48/72/96/144/192dp
gen_layer() {           # gen_layer <dp> <out.png> <ratio> [round]
  local dp=$1 out=$2 ratio=$3 round=${4:-}
  local px=$(( dp ))
  local inner; inner=$(python3 -c "print(max(1,round($px*$ratio)))")
  local src_sq; src_sq=$(python3 -c "print($inner)")
  local off; off=$(python3 -c "print(($px-$src_sq)//2)")
  local args=( -size "${px}x${px}" xc:none )
  [[ -n $round ]] && args=( -size "${px}x${px}" xc:black -fill white \
                            -draw "circle $((px/2)),$((px/2)) $((px/2)),0" -alpha off )
  convert "${args[@]}" \
    \( "$SRC" -resize "${src_sq}x${src_sq}" \) -geometry "+${off}+${off}" -composite \
    PNG32:"$out"
}

gen_foreground() {      # 内容缩到安全区内，保留原奶油底以便与背景层融合
  local dir=$1 dp=$2
  local px=$(( dp * 108 / 48 ))
  local tmp; tmp=$(mktemp --suffix=.png)
  convert "$SRC" -resize "$(( px * 588 / 1000 ))x$(( px * 588 / 1000 ))" PNG32:"$tmp"
  convert -size "${px}x${px}" xc:none "$tmp" -gravity center -composite \
          -quality 90 "$dir/ic_launcher_foreground.webp"
  rm -f "$tmp"
}

gen_monochrome() {      # 主题图标：非奶油部分作为剪影，Android 负责着色
  local dir=$1 dp=$2
  local px=$(( dp * 108 / 48 ))
  local inner=$(( px * 588 / 1000 ))
  local off=$(( (px - inner) / 2 ))
  local tmp; tmp=$(mktemp --suffix=.png)
  # 背景是奶油色渐变（#F5F5F1..#F9F9F7），单色 -opaque 匹配不到，改按亮度取剪影。
  # 灰度实测：背景 0.967 / 青色波形 0.793 / 深色描边 ~0.13。
  # 阈值取 82%：高于此值判白（仅背景），低于判黑（描边与波形均保留）。
  # 若取 78% 则青色波形 0.793 会被误判为白，波形在主题图标中消失。
  convert "$SRC" -resize "${inner}x${inner}" \
          -colorspace Gray -threshold 82% \
          -alpha off PNG32:"$tmp"
  convert -size "${px}x${px}" xc:none "$tmp" -geometry "+${off}+${off}" -composite \
          -background white -alpha remove -alpha off \
          -quality 90 "$dir/ic_launcher_monochrome.webp"
  rm -f "$tmp"
}

echo "adaptive foreground / monochrome:"
for spec in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  d=${spec%%:*}; dp=${spec##*:}
  mkdir -p "$RES/mipmap-$d"
  gen_foreground "$RES/mipmap-$d" "$dp"
  gen_monochrome "$RES/mipmap-$d" "$dp"
  echo "  mipmap-$d  foreground + monochrome  ${dp}dpi"
done

echo "legacy launcher icons:"
for spec in mdpi:48 hdpi:72 xhdpi:96 xxhdpi:144 xxxhdpi:192; do
  d=${spec%%:*}; dp=${spec##*:}
  gen_layer "$dp" /tmp/opencode/_sq.png 0.92
  convert /tmp/opencode/_sq.png -quality 90 "$RES/mipmap-$d/ic_launcher.webp"
  # 圆形版本：按 0.92 缩放后套圆形遮罩
  convert /tmp/opencode/_sq.png \
    \( +clone -alpha extract -fill black -colorize 100 \
       -fill white -draw "circle $((dp/2)),$((dp/2)) $((dp/2)),0" \
       -alpha off \) -compose CopyOpacity -composite \
    -quality 90 "$RES/mipmap-$d/ic_launcher_round.webp"
  echo "  mipmap-$d  ic_launcher + ic_launcher_round  ${dp}px"
done
rm -f /tmp/opencode/_sq.png

echo
echo "done"