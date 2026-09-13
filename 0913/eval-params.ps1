# eval-params.ps1 —— topK × similarityThreshold 参数扫描评测
# 用新生手册的 10 道题做基准，计算 Recall@k 与 MRR

[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$ErrorActionPreference = 'Continue'

$baseUrl = 'http://127.0.0.1:8080'
$tmp = Join-Path $PSScriptRoot 'sweep-tmp'
New-Item -ItemType Directory -Force -Path $tmp | Out-Null

function WriteU8($p, $s) { [System.IO.File]::WriteAllText($p, $s, (New-Object System.Text.UTF8Encoding($false))) }
function ReadU8($p) { [System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8) }

# ============ Ground Truth ============
# headings: 答案所在的章节名（模糊匹配）。多源题给多个章节，命中任一算命中。
$questions = @(
    @{ id = 'F01'; q = '集美学校校歌的歌词，最后一句是什么？';                            headings = @('集美学校校歌');                     answerable = $true },
    @{ id = 'F02'; q = '新生办理落户的截止日期是什么时候？';                              headings = @('户口迁移相关规定');                 answerable = $true },
    @{ id = 'F03'; q = '本科艺术类专业的学费和住宿费分别是多少？';                        headings = @('收费标准一览表');                   answerable = $true },
    @{ id = 'F04'; q = '新生办理户口迁入时，迁入地址怎么填？所属派出所是哪个？';          headings = @('户口迁移相关规定');                 answerable = $true },
    @{ id = 'F05'; q = '请概括一下集美大学本科新生的报到流程。';                          headings = @('报到时间','接站程序','办理入学手续程序'); answerable = $true },
    @{ id = 'F06'; q = '宿舍需要自带哪些生活用品？学校提供了什么？';                      headings = @('生活用品要求');                     answerable = $true },
    @{ id = 'F07'; q = '学校对学生驾驶交通工具进入校园有哪些规定？';                      headings = @('校园交通');                         answerable = $true },
    @{ id = 'F08'; q = '集美大学2025年在各省的本科录取分数线是多少？';                    headings = @();                                   answerable = $false },
    @{ id = 'F09'; q = '集美大学目前共有多少个本科专业？';                                headings = @();                                   answerable = $false },
    @{ id = 'F10'; q = '学校图书馆的开放时间是几点到几点？';                              headings = @();                                   answerable = $false }
)

$answerable = @($questions | Where-Object { $_.answerable })
$absent = @($questions | Where-Object { -not $_.answerable })

# ============ 扫描网格 ============
$topKs = 3, 5, 8, 10, 15
$thresholds = 0.0, 0.5, 0.6, 0.65, 0.70

$rows = @()

foreach ($k in $topKs) {
    foreach ($th in $thresholds) {

        $hitCount = 0
        $mrrSum = 0.0
        $emptyCount = 0
        $returnedSum = 0
        $ranks = @()

        foreach ($item in $answerable) {
            $body = '{"query":"' + $item.q + '","topK":' + $k + ',"similarityThreshold":' + $th + '}'
            $bf = Join-Path $tmp 'req.json'
            $rf = Join-Path $tmp 'resp.json'
            WriteU8 $bf $body
            curl.exe -s -o $rf -X POST "$baseUrl/api/kb/search" -H 'Content-Type: application/json' `
                --data-binary "@$bf" --max-time 180 2>$null | Out-Null
            try { $resp = ReadU8 $rf | ConvertFrom-Json } catch { $resp = $null }

            if (-not $resp -or -not $resp.results -or $resp.results.Count -eq 0) {
                $emptyCount++
                $ranks += 0
                continue
            }
            $returnedSum += $resp.results.Count

            # 找第一个命中的排名
            $rank = 0
            for ($i = 0; $i -lt $resp.results.Count; $i++) {
                $h = [string]$resp.results[$i].heading
                foreach ($want in $item.headings) {
                    if ($h -like "*$want*") { $rank = $i + 1; break }
                }
                if ($rank -gt 0) { break }
            }
            if ($rank -gt 0) { $hitCount++; $mrrSum += 1.0 / $rank }
            $ranks += $rank
        }

        $rows += [pscustomobject]@{
            topK       = $k
            threshold  = $th
            recall     = $hitCount
            total      = $answerable.Count
            recallPct  = [math]::Round($hitCount * 100.0 / $answerable.Count, 1)
            mrr        = [math]::Round($mrrSum / $answerable.Count, 3)
            avgReturn  = [math]::Round($returnedSum * 1.0 / $answerable.Count, 1)
            empty      = $emptyCount
        }
    }
}

# ============ 输出 ============
Write-Host ""
Write-Host "=================== 参数扫描结果 ===================" -ForegroundColor Cyan
Write-Host ("基准: 新生手册 {0} 道可答题(另有 {1} 道库外题不计入 Recall)" -f $answerable.Count, $absent.Count)
Write-Host ""
Write-Host ("{0,-7} {1,-9} {2,-14} {3,-9} {4,-14} {5}" -f 'topK', '阈值', 'Recall@k', 'MRR', '平均返回条数', '空结果数')
Write-Host ("-" * 74)
foreach ($r in $rows) {
    Write-Host ("{0,-7} {1,-9} {2,-14} {3,-9} {4,-14} {5}" -f $r.topK, $r.threshold,
        ("{0}/{1}={2}%" -f $r.recall, $r.total, $r.recallPct), $r.mrr, $r.avgReturn, $r.empty)
}

# 最佳组合：先看 MRR，再看 Recall
$best = $rows | Sort-Object -Property @{E = { $_.mrr }; Descending = $true }, @{E = { $_.recall }; Descending = $true } | Select-Object -First 1
Write-Host ""
Write-Host "=================== 最佳组合 ===================" -ForegroundColor Green
Write-Host ("  topK = {0}, similarityThreshold = {1}" -f $best.topK, $best.threshold) -ForegroundColor Green
Write-Host ("  Recall@k = {0}/{1} ({2}%)   MRR = {3}" -f $best.recall, $best.total, $best.recallPct, $best.mrr) -ForegroundColor Green

$rows | Export-Csv -Path (Join-Path $tmp 'sweep-result.csv') -NoTypeInformation -Encoding UTF8
Write-Host ""
Write-Host ("明细已存: " + (Join-Path $tmp 'sweep-result.csv')) -ForegroundColor DarkGray
