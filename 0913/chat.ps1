<#
    chat.ps1  —  交互式多轮对话客户端

    用法:
        powershell -ExecutionPolicy Bypass -File chat.ps1
        powershell -ExecutionPolicy Bypass -File chat.ps1 -ConversationId <已有的会话ID>
        powershell -ExecutionPolicy Bypass -File chat.ps1 -BaseUrl http://127.0.0.1:8080

    直接在提示符下输入问题即可,conversationId 由脚本自动维护,
    同一进程内的多轮问答会自动带上上下文。

    内置命令(以 / 开头):
        /new      开一个新会话(丢弃当前上下文,换新 ID)
        /clear    清空当前会话的记忆(保留 ID)
        /id       显示当前会话 ID
        /history  列出当前会话窗口内的消息
        /help     显示帮助
        /exit     退出(也可按 Ctrl+C)

    注意:本文件必须保存为 UTF-8 with BOM。
#>

[CmdletBinding()]
param(
    [string]$ConversationId,
    [string]$BaseUrl = 'http://127.0.0.1:8080'
)

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

$script:cid   = $ConversationId
$script:turn  = 0
$TMP = Join-Path $env:TEMP ('rag-chat-' + [guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $TMP | Out-Null

function WriteU8($p, $s) { [System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false))) }
function ReadU8($p) { [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8) }

function Show-Help {
    Write-Host ""
    Write-Host "  直接输入问题回车即可,可连续追问。" -ForegroundColor Gray
    Write-Host "  命令:" -ForegroundColor Gray
    Write-Host "    /new      开新会话(丢弃上下文)" -ForegroundColor Gray
    Write-Host "    /clear    清空当前会话记忆(保留 ID)" -ForegroundColor Gray
    Write-Host "    /id       显示当前会话 ID" -ForegroundColor Gray
    Write-Host "    /history  查看当前会话窗口内消息" -ForegroundColor Gray
    Write-Host "    /exit     退出" -ForegroundColor Gray
    Write-Host ""
}

function Test-App {
    $code = curl.exe -s -o NUL -w '%{http_code}' "$BaseUrl/api/kb/default-path" --max-time 8 2>$null
    return ($code -eq '200')
}

function Invoke-Ask($message) {
    $payload = @{ message = $message }
    if ($script:cid) { $payload.conversationId = $script:cid }
    $json = $payload | ConvertTo-Json -Compress
    $bf = Join-Path $TMP 'req.json'
    $of = Join-Path $TMP 'resp.json'
    WriteU8 $bf $json

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $code = curl.exe -s -o $of -w '%{http_code}' -X POST "$BaseUrl/api/chat" `
        -H 'Content-Type: application/json' --data-binary "@$bf" --max-time 600 2>$null
    $sw.Stop()

    $body = ReadU8 $of
    if ($code -ne '200') {
        Write-Host ("  [错误] HTTP $code") -ForegroundColor Red
        if ($body) { Write-Host ("  " + $body) -ForegroundColor DarkRed }
        return
    }
    try {
        $r = $body | ConvertFrom-Json
    } catch {
        Write-Host ("  [错误] 响应无法解析: " + $body) -ForegroundColor Red
        return
    }
    # 首轮由服务端生成会话 ID,这里接住
    if (-not $script:cid -and $r.conversationId) { $script:cid = $r.conversationId }
    $script:turn++

    Write-Host ""
    Write-Host ("AI > ") -NoNewline -ForegroundColor Cyan
    Write-Host $r.reply
    Write-Host ("     (" + [math]::Round($sw.Elapsed.TotalSeconds, 1) + "s, 第 " + $script:turn + " 轮)") -ForegroundColor DarkGray
    Write-Host ""
}

function Invoke-History {
    if (-not $script:cid) { Write-Host "  当前还没有会话。" -ForegroundColor Yellow; return }
    $of = Join-Path $TMP 'hist.json'
    curl.exe -s -o $of "$BaseUrl/api/chat/$($script:cid)/messages" --max-time 30 2>$null | Out-Null
    try { $h = (ReadU8 $of) | ConvertFrom-Json } catch { Write-Host "  读取失败" -ForegroundColor Red; return }
    Write-Host ("  会话 " + $script:cid + " 共 " + $h.count + " 条消息:") -ForegroundColor Gray
    foreach ($m in $h.messages) {
        $color = 'Gray'
        if ($m.type -eq 'USER') { $color = 'White' }
        Write-Host ("    [" + $m.type + "] " + $m.text) -ForegroundColor $color
    }
}

function Invoke-Clear {
    if (-not $script:cid) { Write-Host "  当前还没有会话。" -ForegroundColor Yellow; return }
    $r = curl.exe -s -X DELETE "$BaseUrl/api/chat/$($script:cid)" --max-time 30 2>$null
    Write-Host ("  " + $r) -ForegroundColor Yellow
    $script:turn = 0
}

# ---------------- 启动 ----------------
Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host " RAG 多轮对话客户端" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

if (-not (Test-App)) {
    Write-Host ""
    Write-Host " 应用不可达: $BaseUrl" -ForegroundColor Red
    Write-Host " 请先确认应用已启动(依赖:Milvus + LM Studio)。" -ForegroundColor Yellow
    Write-Host ""
    exit 1
}

if ($script:cid) {
    Write-Host (" 续接已有会话: " + $script:cid) -ForegroundColor Green
} else {
    Write-Host " 新会话(首轮提问后由服务端分配 ID)" -ForegroundColor Green
}
Show-Help

# ---------------- 主循环 ----------------
while ($true) {
    $line = $null
    try {
        $line = Read-Host "你 "
    } catch {
        break   # 输入流结束(如管道 / Ctrl+Z)
    }
    if ($null -eq $line) { break }

    $text = $line.Trim()
    if ($text -eq '') { continue }

    # 注意:这里不能用 switch —— switch 内的 continue 只作用于 switch 自身,
    # 不会跳过外层 while 的剩余语句,会导致命令执行后仍落入兜底分支。
    # 用 if/elseif 才能正确 continue 外层循环。
    if ($text -match '^/(exit|quit|q)$') {
        Write-Host " 再见。" -ForegroundColor Gray
        return
    } elseif ($text -match '^/(help|h|\?)$') {
        Show-Help
        continue
    } elseif ($text -eq '/new') {
        $script:cid = $null; $script:turn = 0
        Write-Host " 已开启新会话(首轮提问后分配新 ID)。" -ForegroundColor Green
        continue
    } elseif ($text -eq '/clear') {
        Invoke-Clear
        continue
    } elseif ($text -eq '/id') {
        if ($script:cid) {
            Write-Host ("  当前会话 ID: " + $script:cid) -ForegroundColor Green
        } else {
            Write-Host "  尚未分配(ID 在首轮提问后生成)。" -ForegroundColor Yellow
        }
        continue
    } elseif ($text -eq '/history') {
        Invoke-History
        continue
    } elseif ($text.StartsWith('/')) {
        Write-Host "  未知命令,输入 /help 查看。" -ForegroundColor Yellow
        continue
    }

    Invoke-Ask $text
}
