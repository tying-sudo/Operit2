import { build } from 'esbuild';
import { zipSync } from 'fflate';
import { readFile, readdir, mkdir, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));

/** Enumerates editable package sources without including dependencies or previous artifacts. */
async function sources(directory) {
  const files = [];
  for (const entry of await readdir(path.join(root, directory), { withFileTypes: true })) {
    const name = `${directory}/${entry.name}`;
    if (entry.isDirectory()) files.push(...await sources(name));
    else if (entry.isFile()) files.push(name);
    else throw new Error(`Unsupported package entry: ${name}`);
  }
  return files.sort();
}

/** Bundles the registration and the tool namespace. */
export async function bundles() {
  const files = {};
  for (const entry of ['main', 'packages/accessibility_provider']) {
    const source = await readFile(path.join(root, `src/${entry}.ts`), 'utf8');
    const metadata = entry === 'packages/accessibility_provider' ? source.match(/^\/\* METADATA[\s\S]*?\*\//)?.[0] : undefined;
    if (entry === 'packages/accessibility_provider' && !metadata) throw new Error('Missing unified tool metadata');
    const result = await build({ absWorkingDir: root, entryPoints: [`src/${entry}.ts`], bundle: true, write: false, format: 'cjs', platform: 'neutral', target: 'es2020', supported: { 'async-await': false }, charset: 'utf8',
      external: [], ...(metadata ? { banner: { js: metadata } } : {}) });
    files[`dist/${entry}.js`] = result.outputFiles[0].contents;
  }
  return files;
}

/** Packages one plugin owner with its public tool namespace and its installer page. */
export async function buildPackage() {
  const files = await bundles();
  for (const name of ['manifest.json', 'package.json', 'pnpm-lock.yaml', 'tsconfig.json', 'README.md', ...await sources('src'), ...await sources('scripts'), ...await sources('resources')]) files[name] = new Uint8Array(await readFile(path.join(root, name)));
  for (const [name, bytes] of Object.entries(files)) {
    if (!name.startsWith('dist/')) continue;
    const target = path.join(root, name);
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, bytes);
  }
  const archive = new Uint8Array(zipSync(files, { level: 6 }));
  await writeFile(path.join(root, 'dist/accessibility_provider.toolpkg'), archive);
  console.log(`Packed accessibility_provider.toolpkg (${archive.length} bytes)`);
}

await buildPackage();
