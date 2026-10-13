# Bundled chart libraries

These distributables are pinned and included in the APK for offline rendering.

| Asset | Version | SHA-256 |
| --- | --- | --- |
| mermaid.min.js | 11.17.2 | 581ed7d74bd9048d0e3a91363927d72ef22942d7722546b27f7cc29e35390eb8 |
| echarts.min.js | 6.1.0 | b66b25aeb4df84e33199dc21694014d336d222cbd9deb0e5a7c14bd6aa0d0fd0 |

Original packages:

- https://registry.npmjs.org/mermaid/-/mermaid-11.17.2.tgz (`dist/mermaid.min.js`, `LICENSE`)
- https://registry.npmjs.org/echarts/-/echarts-6.1.0.tgz (`dist/echarts.min.js`, `LICENSE`, `NOTICE`, `licenses/LICENSE-d3`)

The Mermaid distributable retains embedded notices for its bundled dependencies. Do not strip license comments. `renderer.js` and `circuit.js` are Lyra's own integration/rendering code under AGPL-3.0. `circuit-symbols.js` includes selected MIT-licensed Schemdraw 0.23 geometry and Lyra's own IEC-style functional symbols. Preserve `SCHEMDRAW-LICENSE` and the generated provenance header. The exact same catalog is packaged as the native JVM resource `charts/circuit-symbols.json`. Python/Schemdraw are needed only to regenerate the catalog, never to build or run the APK.

Regenerate with Schemdraw 0.23 installed in an isolated directory:

```powershell
python -m pip install --no-deps --target app/build/circuit-vendor schemdraw==0.23
python tools/generate_circuit_symbols.py --vendor app/build/circuit-vendor
node --test app/src/test/charts/circuit.test.cjs
```

Commit both generated snapshots and gallery fixtures together. The generator validates allowed SVG tags/attributes; model documents cannot provide SVG templates. Native and browser catalog equality is covered by tests. The catalog is a practical IEC-style schematic library, not a complete or certified IEC 60617 database. The JSON contract and symbol discovery tool are documented in `docs/CHARTS.md`. API references: https://mermaid.js.org/config/usage.html and https://echarts.apache.org/handbook/en/best-practices/canvas-vs-svg/. Schemdraw source and docs: https://github.com/cdelker/schemdraw and https://schemdraw.readthedocs.io/en/0.23/.
