"""
cortar_sprites.py
-----------------
Corta o sprite sheet do ônibus em sprites individuais por direção.

Layout esperado do grid (baseado na imagem do Gemini):

    Linha 0 (topo):    NO    N_diag   N      NE
    Linha 1 (meio):    O     [vazio]  L      (a imagem central é diferente — tratada separado)
    Linha 2 (baixo):   SO    S_diag   S      SE

Como o sheet tem 4 colunas e 3 linhas visíveis + possivelmente uma
sprite central extra, o script também tem um MODO MANUAL onde você
informa quantas colunas/linhas e quais são os ângulos de cada célula.

USO:
    python cortar_sprites.py --input sprite_sheet.png --output ./sprites --cols 4 --rows 3

    # Modo automático (detecta fundo transparente e recorta cada objeto):
    python cortar_sprites.py --input sprite_sheet.png --output ./sprites --auto

DEPENDÊNCIAS: Pillow (pip install pillow)
"""

import argparse
import os
import sys
from pathlib import Path

try:
    from PIL import Image, ImageChops
except ImportError:
    sys.exit("Pillow não encontrado. Instale com: pip install pillow")


# ---------------------------------------------------------------------------
# Mapeamento padrão: (linha, coluna) -> nome do sprite
# Ajuste aqui se o layout do seu sheet for diferente!
# ---------------------------------------------------------------------------
DEFAULT_GRID_NAMES = {
    (0, 0): "bus_no",   # Noroeste   315°
    (0, 1): "bus_n2",   # Norte diagonal (pode descartar ou usar como N)
    (0, 2): "bus_n",    # Norte        0°
    (0, 3): "bus_ne",   # Nordeste    45°
    (1, 0): "bus_o",    # Oeste       270°
    (1, 1): "bus_top",  # Vista de cima (sprite central)
    (1, 2): "bus_l",    # Leste        90°
    (1, 3): "bus_l2",   # Leste extra  (pode descartar)
    (2, 0): "bus_so",   # Sudoeste    225°
    (2, 1): "bus_s2",   # Sul diagonal
    (2, 2): "bus_s",    # Sul         180°
    (2, 3): "bus_se",   # Sudeste     135°
}

# Sprites que realmente importam para o sistema de 8 direções
DIRECOES_8 = ["bus_n", "bus_ne", "bus_l", "bus_se", "bus_s", "bus_so", "bus_o", "bus_no"]


def cortar_grid(img: Image.Image, cols: int, rows: int, output_dir: Path,
                nomes: dict, padding: int = 0) -> list[Path]:
    """Corta o sheet em células iguais e salva cada uma."""
    w, h = img.size
    cell_w = w // cols
    cell_h = h // rows
    salvos = []

    for row in range(rows):
        for col in range(cols):
            nome = nomes.get((row, col), f"sprite_{row}_{col}")
            x0 = col * cell_w + padding
            y0 = row * cell_h + padding
            x1 = x0 + cell_w - padding * 2
            y1 = y0 + cell_h - padding * 2

            sprite = img.crop((x0, y0, x1, y1))

            # Remove borda transparente excessiva (autocrop)
            sprite = autocrop(sprite)

            dest = output_dir / f"{nome}.png"
            sprite.save(dest, "PNG")
            salvos.append(dest)
            print(f"  ✓ ({row},{col}) -> {dest.name}  [{sprite.size[0]}x{sprite.size[1]}]")

    return salvos


def autocrop(img: Image.Image, min_size: int = 8) -> Image.Image:
    """Remove bordas transparentes ao redor do conteúdo."""
    if img.mode != "RGBA":
        img = img.convert("RGBA")

    # Canal alpha
    alpha = img.split()[3]
    bbox = alpha.getbbox()
    if bbox and (bbox[2] - bbox[0]) > min_size and (bbox[3] - bbox[1]) > min_size:
        return img.crop(bbox)
    return img


def detectar_sprites_auto(img: Image.Image, output_dir: Path,
                           threshold: int = 10) -> list[Path]:
    """
    Modo automático: detecta objetos pelo canal alpha e recorta cada um.
    Útil quando o grid não é perfeitamente uniforme.
    """
    if img.mode != "RGBA":
        img = img.convert("RGBA")

    from PIL import ImageFilter

    alpha = img.split()[3]
    # Binariza o alpha
    alpha_bin = alpha.point(lambda p: 255 if p > threshold else 0)

    # Encontra bounding boxes de regiões conectadas (simples, via scan de linhas)
    w, h = img.size
    pixels = alpha_bin.load()

    visitado = [[False] * h for _ in range(w)]
    bboxes = []

    def flood(sx, sy):
        """BFS para encontrar região conectada."""
        queue = [(sx, sy)]
        min_x, min_y, max_x, max_y = sx, sy, sx, sy
        while queue:
            cx, cy = queue.pop()
            if cx < 0 or cx >= w or cy < 0 or cy >= h:
                continue
            if visitado[cx][cy] or pixels[cx, cy] == 0:
                continue
            visitado[cx][cy] = True
            min_x = min(min_x, cx)
            min_y = min(min_y, cy)
            max_x = max(max_x, cx)
            max_y = max(max_y, cy)
            queue.extend([(cx+1,cy),(cx-1,cy),(cx,cy+1),(cx,cy-1)])
        return (min_x, min_y, max_x+1, max_y+1)

    # Scan em passos de 8px para achar sementes
    STEP = 8
    for x in range(0, w, STEP):
        for y in range(0, h, STEP):
            if pixels[x, y] > threshold and not visitado[x][y]:
                bbox = flood(x, y)
                area = (bbox[2]-bbox[0]) * (bbox[3]-bbox[1])
                if area > 2000:  # ignora ruídos pequenos
                    bboxes.append(bbox)

    # Ordena por posição (cima→baixo, esq→dir)
    bboxes.sort(key=lambda b: (b[1] // 100, b[0]))

    print(f"\n  Detectados {len(bboxes)} objetos automaticamente.\n")
    salvos = []
    for i, bbox in enumerate(bboxes):
        sprite = img.crop(bbox)
        dest = output_dir / f"sprite_auto_{i:02d}.png"
        sprite.save(dest, "PNG")
        print(f"  ✓ sprite_auto_{i:02d}.png  bbox={bbox}  [{sprite.size[0]}x{sprite.size[1]}]")
        salvos.append(dest)

    return salvos


def renomear_interativo(output_dir: Path, salvos: list[Path]):
    """Pergunta ao usuário o ângulo de cada sprite detectado automaticamente."""
    angulos = ["0 (Norte)", "45 (NE)", "90 (Leste)", "135 (SE)",
               "180 (Sul)", "225 (SO)", "270 (Oeste)", "315 (NO)", "pular"]
    nomes_finais = ["bus_n", "bus_ne", "bus_l", "bus_se",
                    "bus_s", "bus_so", "bus_o", "bus_no"]

    print("\n--- RENOMEAÇÃO INTERATIVA ---")
    print("Para cada sprite, informe o ângulo correspondente (ou 'pular'):\n")

    for path in salvos:
        print(f"  Sprite: {path.name}")
        for i, op in enumerate(angulos):
            print(f"    {i}) {op}")
        escolha = input("  Sua escolha: ").strip()
        if escolha.isdigit():
            idx = int(escolha)
            if 0 <= idx < len(nomes_finais):
                novo = output_dir / f"{nomes_finais[idx]}.png"
                path.rename(novo)
                print(f"  → Renomeado para {novo.name}\n")


def gerar_kotlin_helper(output_dir: Path):
    """Gera um snippet Kotlin com o mapeamento ângulo→sprite."""
    snippet = '''
// ============================================================
// BusSpriteHelper.kt  (gerado por cortar_sprites.py)
// Coloque este arquivo em: .../ui/ ou .../util/
// ============================================================

package com.example.cademeuonibus.util

import com.example.cademeuonibus.R

/**
 * Retorna o resource id do sprite do ônibus para um dado azimute (bearing).
 * Os sprites cobrem 8 direções (setores de 45°).
 */
object BusSpriteHelper {

    /**
     * Sprites na ordem N → NE → L → SE → S → SO → O → NO
     * (sentido horário, começando no Norte = 0°)
     */
    private val sprites = arrayOf(
        R.drawable.bus_n,   //   0° – Norte
        R.drawable.bus_ne,  //  45° – Nordeste
        R.drawable.bus_l,   //  90° – Leste
        R.drawable.bus_se,  // 135° – Sudeste
        R.drawable.bus_s,   // 180° – Sul
        R.drawable.bus_so,  // 225° – Sudoeste
        R.drawable.bus_o,   // 270° – Oeste
        R.drawable.bus_no,  // 315° – Noroeste
    )

    /**
     * @param bearing  Azimute em graus (0–360). Pode ser negativo ou > 360.
     * @return         Resource id do drawable correspondente.
     */
    fun spriteForBearing(bearing: Float): Int {
        val normalized = ((bearing % 360f) + 360f) % 360f
        // Offset de 22.5° para centralizar cada setor de 45°
        val index = ((normalized + 22.5f) / 45f).toInt() % 8
        return sprites[index]
    }
}
'''
    dest = output_dir / "BusSpriteHelper.kt"
    dest.write_text(snippet.strip(), encoding="utf-8")
    print(f"\n  ✓ Snippet Kotlin gerado: {dest}")


def gerar_js_helper(output_dir: Path):
    """Gera um snippet JavaScript para uso no map.html (Leaflet)."""
    snippet = '''
// ============================================================
// bus_sprite_helper.js  (gerado por cortar_sprites.py)
// Inclua no seu map.html ou cole diretamente no <script>
// ============================================================

const BUS_SPRITE_DIRS = ['n','ne','l','se','s','so','o','no'];

/**
 * Retorna um L.Icon do Leaflet para o azimute informado.
 * @param {number} bearing  - Azimute em graus (0-360)
 * @param {string} basePath - Pasta onde estão os PNGs (ex: 'assets/sprites/')
 */
function getBusIcon(bearing, basePath = 'sprites/') {
    const normalized = ((bearing % 360) + 360) % 360;
    const index = Math.floor((normalized + 22.5) / 45) % 8;
    const dir = BUS_SPRITE_DIRS[index];

    return L.icon({
        iconUrl: `${basePath}bus_${dir}.png`,
        iconSize:   [48, 48],
        iconAnchor: [24, 24],   // centro da imagem
        popupAnchor:[0, -24]
    });
}

// Uso ao atualizar posição do ônibus:
// marker.setIcon(getBusIcon(vehicle.bearing, 'file:///android_asset/sprites/'));
'''
    dest = output_dir / "bus_sprite_helper.js"
    dest.write_text(snippet.strip(), encoding="utf-8")
    print(f"  ✓ Snippet JS gerado: {dest}")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def main():
    parser = argparse.ArgumentParser(
        description="Corta sprite sheet de ônibus em sprites por direção."
    )
    parser.add_argument("--input",  "-i", required=True, help="Caminho para o sprite sheet (PNG)")
    parser.add_argument("--output", "-o", default="./sprites", help="Pasta de saída (default: ./sprites)")
    parser.add_argument("--cols",   "-c", type=int, default=4,  help="Nº de colunas do grid (default: 4)")
    parser.add_argument("--rows",   "-r", type=int, default=3,  help="Nº de linhas do grid (default: 3)")
    parser.add_argument("--padding","-p", type=int, default=4,  help="Padding interno por célula em px (default: 4)")
    parser.add_argument("--auto",   action="store_true",        help="Detecção automática de sprites por alpha")
    parser.add_argument("--rename", action="store_true",        help="Renomeação interativa após corte automático")
    parser.add_argument("--kotlin", action="store_true", default=True, help="Gera BusSpriteHelper.kt")
    parser.add_argument("--js",     action="store_true", default=True, help="Gera bus_sprite_helper.js")
    args = parser.parse_args()

    input_path = Path(args.input)
    if not input_path.exists():
        sys.exit(f"Arquivo não encontrado: {input_path}")

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    print(f"\n📂 Entrada : {input_path}")
    print(f"📂 Saída   : {output_dir.resolve()}\n")

    img = Image.open(input_path).convert("RGBA")
    print(f"📐 Tamanho : {img.size[0]}x{img.size[1]}px\n")

    if args.auto:
        print("🔍 Modo automático — detectando sprites por canal alpha...\n")
        salvos = detectar_sprites_auto(img, output_dir)
        if args.rename:
            renomear_interativo(output_dir, salvos)
    else:
        print(f"✂️  Cortando grid {args.rows}×{args.cols}...\n")
        salvos = cortar_grid(img, args.cols, args.rows, output_dir,
                             DEFAULT_GRID_NAMES, padding=args.padding)

    print(f"\n✅ {len(salvos)} sprites salvos em '{output_dir.resolve()}'")

    # Helpers de código
    if args.kotlin:
        gerar_kotlin_helper(output_dir)
    if args.js:
        gerar_js_helper(output_dir)

    # Resumo dos sprites para 8 direções
    print("\n🗺️  Sprites para o sistema de 8 direções:")
    for nome in DIRECOES_8:
        f = output_dir / f"{nome}.png"
        status = "✓" if f.exists() else "✗ (não encontrado)"
        print(f"   {status}  {nome}.png")

    print("\nPróximo passo:")
    print("  1. Copie os bus_*.png para: app/src/main/res/drawable/")
    print("  2. Copie BusSpriteHelper.kt para: .../util/")
    print("  3. Copie bus_sprite_helper.js para: app/src/main/assets/")
    print("  4. Integre no MainViewModel.kt e map.html.\n")


if __name__ == "__main__":
    main()
