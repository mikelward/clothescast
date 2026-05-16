import os
import xml.etree.ElementTree as ET

def fix_collar(file_path):
    with open(file_path, 'r') as f:
        content = f.read()

    # Old collar code usually looked like this:
    # M34,14 Q39,30 48,30 Q57,30 62,14 Q56,21 48,21 Q40,21 34,14 Z (T-Shirt)
    # M35,14 Q39,31 48,31 Q57,31 61,14 Q55,21 48,21 Q41,21 35,14 Z (Sweater)

    # We want it to be a solid darkened piece filling up to the top border,
    # essentially matching the top border contour.

    # The new top border contour we used is:
    # L34,14 Q48,6 62,14 (for t-shirt, polo)
    # L35,14 Q48,6 61,14 (for sweater)

    # Let's replace the collar paths to fill the whole neck space.
    if "ic_outfit_tshirt.xml" in file_path:
        content = content.replace(
            'M34,14 Q39,30 48,30 Q57,30 62,14 Q56,21 48,21 Q40,21 34,14 Z',
            'M34,14 Q39,30 48,30 Q57,30 62,14 Q48,6 34,14 Z'
        )
    elif "ic_outfit_polo.xml" in file_path:
        # For polo, the inner fill was M34,14 L45,27 L39,34 L32,17 Z etc
        # We can make the base collar fill the whole gap:
        # Instead of doing that, let's just make the bib part use the Q48,6 to close it perfectly.
        # However polo doesn't have the bib crescent, it has a slit and collar flaps.
        pass
    elif "ic_outfit_sweater.xml" in file_path:
        content = content.replace(
            'M35,14 Q39,31 48,31 Q57,31 61,14 Q55,21 48,21 Q41,21 35,14 Z',
            'M35,14 Q39,31 48,31 Q57,31 61,14 Q48,6 35,14 Z'
        )
    elif "ic_outfit_thin_jacket.xml" in file_path:
        content = content.replace(
            'M36,14 Q48,6 60,14 L60,24 Q54,31 48,31 Q42,31 36,24 Z',
            'M36,14 L36,24 Q42,31 48,31 Q54,31 60,24 L60,14 Q48,6 36,14 Z'
        )
    elif "ic_outfit_thick_jacket.xml" in file_path:
        content = content.replace(
            'M35,13 Q48,2 61,13 Q56,32 48,32 Q40,32 35,13 Z',
            'M35,13 Q40,32 48,32 Q56,32 61,13 Q48,2 35,13 Z'
        )
    elif "ic_outfit_thick_coat.xml" in file_path:
        content = content.replace(
            'M35,12 Q48,-2 61,12 Q56,36 48,36 Q40,36 35,12 Z',
            'M35,12 Q40,36 48,36 Q56,36 61,12 Q48,-2 35,12 Z'
        )
    elif "ic_outfit_puffer_jacket.xml" in file_path:
        content = content.replace(
            'M35,13 Q48,0 61,13 Q56,30 48,30 Q40,30 35,13 Z',
            'M35,13 Q40,30 48,30 Q56,30 61,13 Q48,0 35,13 Z'
        )

    with open(file_path, 'w') as f:
        f.write(content)

for file in os.listdir('app/src/main/res/drawable/'):
    if file.startswith('ic_outfit_') and file.endswith('.xml'):
        fix_collar(os.path.join('app/src/main/res/drawable/', file))
