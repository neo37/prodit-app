// afterPack для electron-builder: иконка и сведения о версии в «Production IT.exe» без wine/rcedit (чистый JS — resedit).
// electron-builder на Linux меняет exe через rcedit под wine; у нас signAndEditExecutable=false, поэтому делаем это здесь.
const fs = require('fs');
const path = require('path');

module.exports = async function afterPack(ctx) {
  if (ctx.electronPlatformName !== 'win32') return;
  const { NtExecutable, NtExecutableResource, Data, Resource } = await import('resedit');
  const info = ctx.packager.appInfo;
  const exePath = path.join(ctx.appOutDir, `${info.productFilename}.exe`);
  const exe = NtExecutable.from(fs.readFileSync(exePath), { ignoreCert: true });
  const res = NtExecutableResource.from(exe);
  const ico = Data.IconFile.from(fs.readFileSync(path.join(__dirname, '..', 'build', 'icon.ico')));
  const groups = Resource.IconGroupEntry.fromEntries(res.entries);
  const targets = groups.length ? groups.map((g) => [g.id, g.lang]) : [[1, 1033]];
  for (const [id, lang] of targets) {
    Resource.IconGroupEntry.replaceIconsForResource(res.entries, id, lang, ico.icons.map((i) => i.data));
  }
  const vis = Resource.VersionInfo.fromEntries(res.entries);
  const vi = vis[0] || Resource.VersionInfo.createEmpty();
  const [a, b, c] = info.version.split('.').map((n) => parseInt(n, 10) || 0);
  vi.setFileVersion(a, b, c, 0, 1033);
  vi.setProductVersion(a, b, c, 0, 1033);
  const langs = vi.getAllLanguagesForStringValues();
  for (const l of (langs.length ? langs : [{ lang: 1033, codepage: 1200 }])) {
    vi.setStringValues(l, {
      ProductName: info.productName, FileDescription: info.productName, CompanyName: 'Production IT',
      LegalCopyright: info.copyright || '', OriginalFilename: `${info.productFilename}.exe`, InternalName: info.productFilename,
      FileVersion: info.version, ProductVersion: info.version,
    });
  }
  vi.outputToResourceEntries(res.entries);
  res.outputResource(exe);
  fs.writeFileSync(exePath, Buffer.from(exe.generate()));
  console.log(`  • иконка и версия записаны в ${path.basename(exePath)} (групп иконок: ${targets.length})`);
};
