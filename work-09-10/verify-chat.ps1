<#
    verify-chat.ps1  —  多轮聊天接口验证脚本

    用法(在项目根目录或任意位置):
        powershell -ExecutionPolicy Bypass -File verify-chat.ps1

    说明:
      * 本脚本只做只读验证,不会修改任何代码或数据
      * 会自动检查环境(应用 / Milvus),然后跑 6 个用例
      * 全部通过退出码 0,有失败退出码 1

    注意:本文件必须保存为 UTF-8 with BOM,否则 Windows PowerShell 5.1
          会把中文按 GBK 解读导致乱码。
#>

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

$BASE = 'http://127.0.0.1:8080'
$TMP  = Join-Path $PSScriptRoot '.verify-chat-tmp'
New-Item -ItemType Directory -Force -Path $TMP | Out-Null

function WriteU8($p, $s) { [System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false))) }
function ReadU8($p) { [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8) }

$script:pass = 0
$script:fail = 0

function Check($name, $ok, $detail) {
    if ($ok) {
        $script:pass++
        Write-Host ("  [通过] " + $name) -ForegroundColor Green
    } else {
        $script:fail++
        Write-Host ("  [失败] " + $name) -ForegroundColor Red
    }
    if ($detail) { Write-Host ("         " + $detail) -ForegroundColor DarkGray }
}

function Curl-Post($path, $json, $tag) {
    $bf = Join-Path $TMP "q_$tag.json"
    $of = Join-Path $TMP "r_$tag.json"
    WriteU8 $bf $json
    $code = curl.exe -s -o $of -w '%{http_code}' -X POST "$BASE$path" `
        -H 'Content-Type: application/json' --data-binary "@$bf" --max-time 240 2>$null
    return @{ code = $code; body = (ReadU8 $of) }
}

function Ask($cid, $msg, $tag) {
    if ($cid) {
        $json = '{"conversationId":"' + $cid + '","message":"' + $msg + '"}'
    } else {
        $json = '{"message":"' + $msg + '"}'
    }
    $r = Curl-Post '/api/chat' $json $tag
    try { return ($r.body | ConvertFrom-Json) } catch { return $null }
}

Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host " 多轮聊天接口验证" -ForegroundColor Cyan
Write-Host "==========================================" -ForegroundColor Cyan

# ---------------- 环境自检 ----------------
Write-Host ""
Write-Host "[0] 环境自检" -ForegroundColor Yellow

$appCode = curl.exe -s -o NUL -w '%{http_code}' "$BASE/api/kb/default-path" --max-time 8 2>$null
if ($appCode -ne '200') {
    Write-Host "  应用未就绪(HTTP $appCode)" -ForegroundColor Red
    Write-Host ""
    Write-Host "  请先启动依赖,步骤:" -ForegroundColor Yellow
    Write-Host "    1) 钉住 WSL VM(必须,否则 Milvus 容器会反复重启):"
    Write-Host "       wsl.exe -d Ubuntu -- bash -lc `"sleep 86400`""
    Write-Host "    2) 启动 Milvus:"
    Write-Host "       cd <你的下载目录>\deepseekwork\milvus-deploy"
    Write-Host "       docker compose up -d"
    Write-Host "    3) 在 IDEA 里运行 RagApplication,或用 java -cp 手动启动"
    Write-Host ""
    exit 1
}
Write-Host "  应用可达 ($BASE)" -ForegroundColor Green

[System.IO.File]::WriteAllText((Join-Path $TMP 'empty.json'), '{}', (New-Object System.Text.UTF8Encoding($false)))
$milvus = curl.exe -s -X POST 'http://127.0.0.1:19530/v2/vectordb/collections/list' `
    -H 'Content-Type: application/json' --data-binary "@$TMP\empty.json" --max-time 10 2>$null
if ($milvus -match '"code"\s*:\s*0') {
    Write-Host "  Milvus 可达,集合: $milvus" -ForegroundColor Green
} else {
    Write-Host "  Milvus 不可达或异常: $milvus" -ForegroundColor Red
    Write-Host "  (本脚本用例 1-5 仍可运行,因为多轮聊天不依赖向量库)" -ForegroundColor DarkGray
}

# ---------------- 用例 1 ----------------
Write-Host ""
Write-Host "[1] 单轮兼容:不传 conversationId" -ForegroundColor Yellow
$r1 = Ask $null '你好' 'u1'
Check "返回 200 且服务端生成了 conversationId" `
    (($null -ne $r1) -and ($r1.conversationId -match '^[0-9a-f\-]{36}$') -and ($r1.reply.Length -gt 0)) `
    ("conversationId=" + $r1.conversationId + "  reply=" + $r1.reply)

# ---------------- 用例 2 ----------------
Write-Host ""
Write-Host "[2] 多轮记忆(核心):同一会话能否记住上一轮" -ForegroundColor Yellow
$a1 = Ask $null '我叫张三，请记住我的名字。' 'u2a'
$cid = $a1.conversationId
Write-Host ("         第1轮: " + $a1.reply) -ForegroundColor DarkGray
$a2 = Ask $cid '我叫什么名字？' 'u2b'
Write-Host ("         第2轮: " + $a2.reply) -ForegroundColor DarkGray
Check "第2轮回答中出现「张三」" (($a2.reply -match '张三') -and ($a2.conversationId -eq $cid)) `
    ("会话ID保持一致: " + ($a2.conversationId -eq $cid))

# ---------------- 用例 3 ----------------
Write-Host ""
Write-Host "[3] 会话隔离:两个会话互不串味" -ForegroundColor Yellow
$b1 = Ask $null '我叫李四，请记住。' 'u3a'
$cidB = $b1.conversationId
$c1 = Ask $cid  '我叫什么名字？' 'u3b'
$c2 = Ask $cidB '我叫什么名字？' 'u3c'
Write-Host ("         会话A(张三)追问: " + $c1.reply) -ForegroundColor DarkGray
Write-Host ("         会话B(李四)追问: " + $c2.reply) -ForegroundColor DarkGray
Check "会话A 记得张三且未串到李四" (($c1.reply -match '张三') -and ($c1.reply -notmatch '李四'))
Check "会话B 记得李四且未串到张三" (($c2.reply -match '李四') -and ($c2.reply -notmatch '张三'))

# ---------------- 用例 4 ----------------
Write-Host ""
Write-Host "[4] 会话清空:清空后应遗忘" -ForegroundColor Yellow
$clr = curl.exe -s -X DELETE "$BASE/api/chat/$cid" --max-time 30 2>$null
$d1 = Ask $cid '我叫什么名字？' 'u4a'
Write-Host ("         清空响应: " + $clr) -ForegroundColor DarkGray
Write-Host ("         清空后追问: " + $d1.reply) -ForegroundColor DarkGray
Check "清空后不再记得「张三」" ($d1.reply -notmatch '张三')

# ---------------- 用例 5 ----------------
Write-Host ""
Write-Host "[5] 记忆可观测:查看会话内消息" -ForegroundColor Yellow
$h = curl.exe -s -o "$TMP\h.json" -w '%{http_code}' "$BASE/api/chat/$cidB/messages" --max-time 30 2>$null
$hj = ReadU8 "$TMP\h.json" | ConvertFrom-Json
foreach ($m in $hj.messages) { Write-Host ("         [" + $m.type + "] " + $m.text) -ForegroundColor DarkGray }
Check "HTTP 200 且返回 4 条消息(2 轮问答)" (($h -eq '200') -and ($hj.count -eq 4))

# ---------------- 用例 6 ----------------
Write-Host ""
Write-Host "[6] 接口文档:三个端点均已暴露" -ForegroundColor Yellow
curl.exe -s -o "$TMP\doc.json" "$BASE/v3/api-docs" --max-time 30 2>$null
$doc = ReadU8 "$TMP\doc.json"
$hasPost   = $doc -match '"/api/chat"\s*:\s*\{\s*"post"'
$hasDelete = $doc -match '"/api/chat/\{conversationId\}"\s*:\s*\{\s*"delete"'
$hasGet    = $doc -match '"/api/chat/\{conversationId\}/messages"\s*:\s*\{\s*"get"'
Check "POST /api/chat" $hasPost
Check "DELETE /api/chat/{conversationId}" $hasDelete
Check "GET /api/chat/{conversationId}/messages" $hasGet

# ---------------- 汇总 ----------------
Write-Host ""
Write-Host "==========================================" -ForegroundColor Cyan
if ($script:fail -eq 0) {
    Write-Host (" 全部通过 ($script:pass/$script:pass)") -ForegroundColor Green
} else {
    Write-Host (" 通过 $script:pass 项,失败 $script:fail 项") -ForegroundColor Red
}
Write-Host "==========================================" -ForegroundColor Cyan
Write-Host (" 请求/响应原文: " + $TMP) -ForegroundColor DarkGray
Write-Host ""

if ($script:fail -gt 0) { exit 1 }
exit 0
