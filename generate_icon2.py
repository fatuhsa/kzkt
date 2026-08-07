import sys
from PIL import Image, ImageDraw, ImageFont

def create_icon(size, filename, bg_color):
    img = Image.new("RGBA", (size, size), bg_color)
    draw = ImageDraw.Draw(img)
    
    try:
        # Try finding a bold font
        font_large = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(size * 0.75))
        font_small = ImageFont.truetype("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", int(size * 0.35))
    except IOError:
        font_large = ImageFont.load_default()
        font_small = ImageFont.load_default()

    # We want a large K on the left, and z over t on the right.
    # We will use white color for text.
    text_color = (255, 255, 255, 255)
    
    # Calculate positions
    # K size
    k_bbox = draw.textbbox((0, 0), "K", font=font_large)
    k_w = k_bbox[2] - k_bbox[0]
    k_h = k_bbox[3] - k_bbox[1]

    # z size
    z_bbox = draw.textbbox((0, 0), "z", font=font_small)
    z_w = z_bbox[2] - z_bbox[0]
    z_h = z_bbox[3] - z_bbox[1]
    
    # t size
    t_bbox = draw.textbbox((0, 0), "t", font=font_small)
    t_w = t_bbox[2] - t_bbox[0]
    t_h = t_bbox[3] - t_bbox[1]

    # Padding
    padding = size * 0.1
    
    # Right column x position
    right_x = size / 2 + size * 0.1

    # Draw K
    draw.text((size * 0.15, size / 2 - k_h / 2 - size * 0.15), "K", font=font_large, fill=text_color)
    
    # Draw z (top right)
    draw.text((right_x, size * 0.2), "z", font=font_small, fill=text_color)
    
    # Draw t (bottom right)
    draw.text((right_x, size * 0.55), "t", font=font_small, fill=text_color)
    
    img.save(filename)

# Create launcher icons for all mipmap sizes
create_icon(192, "app/src/main/res/mipmap-xxxhdpi/ic_launcher.png", (0, 0, 0, 0))
create_icon(192, "app/src/main/res/mipmap-xxxhdpi/ic_launcher_round.png", (0, 0, 0, 0))

create_icon(144, "app/src/main/res/mipmap-xxhdpi/ic_launcher.png", (0, 0, 0, 0))
create_icon(144, "app/src/main/res/mipmap-xxhdpi/ic_launcher_round.png", (0, 0, 0, 0))

create_icon(96, "app/src/main/res/mipmap-xhdpi/ic_launcher.png", (0, 0, 0, 0))
create_icon(96, "app/src/main/res/mipmap-xhdpi/ic_launcher_round.png", (0, 0, 0, 0))

create_icon(72, "app/src/main/res/mipmap-hdpi/ic_launcher.png", (0, 0, 0, 0))
create_icon(72, "app/src/main/res/mipmap-hdpi/ic_launcher_round.png", (0, 0, 0, 0))

create_icon(48, "app/src/main/res/mipmap-mdpi/ic_launcher.png", (0, 0, 0, 0))
create_icon(48, "app/src/main/res/mipmap-mdpi/ic_launcher_round.png", (0, 0, 0, 0))

# Create a preview
create_icon(512, "/home/kouzen/.gemini/antigravity-ide/brain/26d047bd-3de3-435d-92e3-8d57d3b7a414/new_icon_preview.png", (20, 20, 20, 255))
