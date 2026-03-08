from PIL import Image
import os
import sys

# Paths
base_img_path = "/Users/jordelmirsdevhome/.gemini/antigravity/brain/85fa0cd8-eebf-4050-b4ee-eea4a6d60f7e/elysium_record_logo_v3_1772979222705.png"
res_dir = "/Users/jordelmirsdevhome/Downloads/celular/ElysiumVanguardRecordShield/android/app/src/main/res"

sizes = {
    "mdpi": 48,
    "hdpi": 72,
    "xhdpi": 96,
    "xxhdpi": 144,
    "xxxhdpi": 192
}

try:
    img = Image.open(base_img_path)
    # Ensure RGBA
    img = img.convert("RGBA")
    
    for density, size in sizes.items():
        density_dir = os.path.join(res_dir, f"mipmap-{density}")
        os.makedirs(density_dir, exist_ok=True)
        
        resized_img = img.resize((size, size), Image.Resampling.LANCZOS)
        
        # Save as ic_launcher and ic_launcher_round
        resized_img.save(os.path.join(density_dir, "ic_launcher.png"), format="PNG")
        resized_img.save(os.path.join(density_dir, "ic_launcher_round.png"), format="PNG")
        print(f"Generated {density} ({size}x{size})")
        
    print("Logo injection successful.")
except Exception as e:
    print(f"Error: {e}")
    sys.exit(1)
