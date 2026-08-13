import { readFile, stat } from 'node:fs/promises'
import { dirname, join, normalize } from 'node:path'
import { fileURLToPath } from 'node:url'

const projectRoot = join(dirname(fileURLToPath(import.meta.url)), '..')
const distRoot = join(projectRoot, 'dist')
const maxInitialBytes = 450 * 1024
const visited = new Set()

async function collectAsset(relativePath) {
  const normalizedPath = normalize(relativePath.replace(/^\//, ''))
  if (visited.has(normalizedPath)) return 0
  visited.add(normalizedPath)

  const absolutePath = join(distRoot, normalizedPath.replace(/^dist[\\/]/, ''))
  const size = (await stat(absolutePath)).size
  if (!absolutePath.endsWith('.js')) return size

  const source = await readFile(absolutePath, 'utf8')
  const imports = [...source.matchAll(/\bimport(?:[\w*{},\s]+from\s*)?["']([^"']+)["']/g)]
    .map((match) => match[1])
    .filter((specifier) => specifier.startsWith('.'))

  let dependencyBytes = 0
  for (const specifier of imports) {
    const dependencyPath = normalize(join(dirname(normalizedPath), specifier))
    dependencyBytes += await collectAsset(dependencyPath)
  }
  return size + dependencyBytes
}

const html = await readFile(join(distRoot, 'index.html'), 'utf8')
const initialAssets = [...html.matchAll(/(?:src|href)="\/?(assets\/[^"]+\.(?:js|css))"/g)]
  .map((match) => match[1])

let initialBytes = 0
for (const asset of initialAssets) initialBytes += await collectAsset(asset)

const kib = (initialBytes / 1024).toFixed(1)
console.log(`Initial static assets: ${kib} KiB across ${visited.size} files`)
if (initialBytes > maxInitialBytes) {
  console.error(`Initial static assets exceed ${(maxInitialBytes / 1024).toFixed(0)} KiB budget`)
  process.exit(1)
}
