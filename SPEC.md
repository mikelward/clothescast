# Clothescast design specs

## Clothing drawables

The `ic_outfit_*` (and matching `ic_notification_top_*`) drawables show up at
two very different sizes: small in lists and notifications, large in the
home-screen widget and outfit detail. Both ends need to look good.

### Glanceability at small sizes

- Each garment must be **clear, readily distinct, and glanceable** at icon
  sizes — a user scanning the Today screen or a notification should
  recognise "t-shirt vs. sweater vs. puffer" without squinting. Silhouette
  does the heavy lifting; fine detail is wasted at 24–48dp.
- Avoid relying on stroke thickness or interior detail to tell two
  garments apart at small sizes. If you have to zoom to see the
  difference, the small-size rendering is wrong.

### Quality at large sizes

- The same drawables must **look great scaled up** in the widget and any
  hero placements. No pixelation, no awkwardly-thin strokes, no detail
  that only made sense at icon size. Vector geometry should hold up to
  several hundred dp.

### Proportions and alignment

- **Waist widths must match** across tops and bottoms so an outfit
  (t-shirt + shorts, sweater + long pants, etc.) lines up cleanly when
  stacked. The bottom edge of a top and the top edge of a bottom should
  meet at the same width.
- **Aspect ratios may be slightly exaggerated** — to make garments look
  distinct from each other, or to make stacked outfits line up — but the
  result must still read as **recognisable and realistic**. Stylised, not
  cartoonish; a sweater that's a bit boxier than reality is fine, a
  sweater shaped like a square is not.

### Default colours

- Default colours should lean **distinctive while still being realistic**.
  Tops have more leeway — a t-shirt can default to red or pink so it
  reads instantly in a list — but garments with a strong real-world
  colour association should respect it. Jeans default to **blue-jeans
  blue**, not red or pink. The test is: would a stranger looking at this
  icon for half a second still recognise it as the right garment?

### Seasonal visual cues

Use consistent cues so the season a garment is meant for reads at a
glance:

- **Summer:** short sleeves, hollow (uncoloured) neckline. Light,
  unencumbered silhouette.
- **Spring / autumn:** longer sleeves, still hollow neckline. Reads as
  "more coverage than summer, not yet winter."
- **Winter:** longest sleeves, **darker neck fill** behind the collar to
  suggest an insulating layer / closed-up neck. Reads as warmest.

The neck treatment (hollow → hollow → dark-filled) and the sleeve length
(short → long → longest) are the primary signals; keep them consistent
across the set so the progression is legible when garments sit next to
each other.
