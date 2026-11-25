param(
  [string]$Json   = "",
  [string]$Words  = "",
  [string]$OutDir = ""
)

$ErrorActionPreference = "Stop"

# ---- defaults -------------------------------------------------------------
if ([string]::IsNullOrWhiteSpace($OutDir)) { $OutDir = $PSScriptRoot }
if ([string]::IsNullOrWhiteSpace($Words))  { $Words  = Join-Path $PSScriptRoot "desc-tier-words.json" }
if ([string]::IsNullOrWhiteSpace($Json)) {
  $Json = "c:\Users\dataeye\.trae-cn\attachments\bfcd54a4-5f6a-4ed0-a922-fe8f1eafc948_b12db268-a51a-471a-8907-0ec78db9f80e_douyin-ai-mix-authors (6).json"
}

if (-not (Test-Path $Json))  { throw "JSON not found: $Json" }
if (-not (Test-Path $Words)) { throw "Words not found: $Words" }

# ---- load ----------------------------------------------------------------
$wordsRaw = Get-Content -Raw -Encoding UTF8 $Words | ConvertFrom-Json
$dataRaw  = Get-Content -Raw -Encoding UTF8 $Json  | ConvertFrom-Json
$authors  = @($dataRaw.authors)

function PrepWordList($arr) {
  $l = @()
  foreach ($w in $arr) { if ($w) { $l += ([string]$w).Trim().ToLower() } }
  return @($l | Sort-Object Length -Descending)
}

$drama = PrepWordList $wordsRaw.drama
$non   = PrepWordList $wordsRaw.nonDrama
$noise = PrepWordList $wordsRaw.noise

# returns list of hit words, long-word-first, each hit removes its text so
# overlapping words (e.g. "AI动画" vs "动画") are not double-counted
function Get-Hits($text, $list) {
  $hits = @()
  $t = $text
  foreach ($w in $list) {
    if ($w.Length -gt 0 -and $t.Contains($w)) {
      $hits += $w
      $t = $t.Replace($w, [string][char]32)
    }
  }
  return $hits
}

# ---- score ---------------------------------------------------------------
$rows = New-Object System.Collections.ArrayList

foreach ($a in $authors) {
  $desc = [string]$a.sampleDesc
  $low  = $desc.ToLower()

  # strip noise words first so promo/contest tags never affect the score
  $t = $low
  $noiseHits = @()
  foreach ($w in $noise) {
    if ($t.Contains($w)) { $noiseHits += $w; $t = $t.Replace($w, [string][char]32) }
  }

  $d = @(Get-Hits $t $drama)
  $t2 = $t
  foreach ($w in $d) { if ($w) { $t2 = $t2.Replace($w, [string][char]32) } }
  $n = @(Get-Hits $t2 $non)

  $score = $d.Count - $n.Count
  if ($score -ge 2)        { $tier = "drama" }
  elseif ($score -le -1)   { $tier = "non-drama" }
  else                     { $tier = "uncertain" }

  [void]$rows.Add([pscustomobject]@{
    tier      = $tier
    nickname  = $a.nickname
    sec_uid   = $a.secUid
    videos    = $a.videoCount
    pos       = $d.Count
    neg       = $n.Count
    score     = $score
    pos_words = ($d -join "|")
    neg_words = ($n -join "|")
    noise     = ($noiseHits -join "|")
    desc      = $desc
  })
}

$rows = @($rows)

# ---- full result ---------------------------------------------------------
$resultPath = Join-Path $OutDir "desc-tier-result.csv"
$rows | Export-Csv -Path $resultPath -NoTypeInformation -Encoding UTF8

# ---- distribution --------------------------------------------------------
$total = $rows.Count
Write-Output ""
Write-Output "==== desc tier distribution ===="
Write-Output ("input  : " + $Json)
Write-Output ("total  : " + $total)
foreach ($t in @("drama","non-drama","uncertain")) {
  $c = @($rows | Where-Object { $_.tier -eq $t }).Count
  $pct = if ($total -gt 0) { [Math]::Round(100.0 * $c / $total, 2) } else { 0 }
  Write-Output ("  {0,-10} {1,6}  {2,6}%" -f $t, $c, $pct)
}
Write-Output ""
Write-Output ("result csv : " + $resultPath)

# ---- stratified sample for manual labeling ------------------------------
$seed   = 20261016
$perTop = 50
$perUnc = 100

$dramaRows = @($rows | Where-Object { $_.tier -eq "drama" })
$nonRows   = @($rows | Where-Object { $_.tier -eq "non-drama" })
$uncRows   = @($rows | Where-Object { $_.tier -eq "uncertain" })

$sD = @($dramaRows | Get-Random -Count ([Math]::Min($perTop, $dramaRows.Count)) -SetSeed $seed)
$sN = @($nonRows   | Get-Random -Count ([Math]::Min($perTop, $nonRows.Count))   -SetSeed ($seed + 1))
$sU = @($uncRows   | Get-Random -Count ([Math]::Min($perUnc, $uncRows.Count))   -SetSeed ($seed + 2))

$sample = @()
foreach ($r in @($sD + $sN + $sU)) {
  $sample += [pscustomobject]@{
    tier         = $r.tier
    nickname     = $r.nickname
    sec_uid      = $r.sec_uid
    videos       = $r.videos
    score        = $r.score
    pos_words    = $r.pos_words
    neg_words    = $r.neg_words
    desc         = $r.desc
    manual_label = ""
  }
}

$samplePath = Join-Path $OutDir "desc-tier-sample-200.csv"
$sample | Export-Csv -Path $samplePath -NoTypeInformation -Encoding UTF8

Write-Output ("sample csv : " + $samplePath + "  (drama " + $sD.Count + " / non-drama " + $sN.Count + " / uncertain " + $sU.Count + ")")
Write-Output ""
Write-Output "columns: tier,nickname,sec_uid,videos,score,pos_words,neg_words,desc,manual_label"
Write-Output "fill manual_label with Y (is short-drama) / N (not) in the sample csv"
Write-Output ""
