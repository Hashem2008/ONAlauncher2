import numpy as np, sys
from PIL import Image
def aces(x): return np.clip((x*(2.51*x+0.03))/(x*(2.43*x+0.59)+0.14),0,1)
def smooth(a,b,x):
    t=np.clip((x-a)/(b-a),0,1); return t*t*(3-2*t)
def mix(a,b,t): return a*(1-t)+b*t
def atmosphere(d, sun, up, rain=0.0):
    mu = np.clip(d@sun, -1, 1)[:,None]
    h = np.clip(d@up, 0, 1)[:,None]
    sunUp = float(sun@up)
    day = smooth(-0.10, 0.25, sunUp)
    dusk = 1.0 - smooth(0.0, 0.45, abs(sunUp))
    night = 1.0 - smooth(-0.25, 0.0, sunUp)
    zenith = mix(np.array([0.30,0.52,0.95]), np.array([0.20,0.24,0.50]), dusk) * (0.35 + 0.65*day)
    zenith = mix(zenith, np.array([0.010,0.016,0.035]), night)
    horizonDay = np.array([0.72,0.84,1.00])
    horizonDusk = np.array([1.00,0.52,0.24])
    toward = (mu*0.5+0.5)**2           # dusk colours gather on the sun's side
    horizon = mix(horizonDay*(0.35+0.65*day), mix(np.array([0.45,0.32,0.38]), horizonDusk, toward), dusk)
    horizon = mix(horizon, np.array([0.015,0.02,0.04]), night)
    k = (1.0 - h)**4
    col = mix(zenith, horizon, k)
    # sun halo (Mie, forward scattering)
    g = 0.76
    mie = (1-g*g)/np.power(1+g*g-2*g*mu, 1.5)
    sunCol = mix(np.array([1.0,0.95,0.85]), np.array([1.0,0.55,0.25]), dusk)
    col += sunCol * mie * 0.012 * (0.4+0.6*day) * (1-night)
    col += sunCol * np.power(np.clip(mu,0,1), 600) * 4.0 * (1-night)   # the disc glow
    lum = col@np.array([0.2126,0.7152,0.0722])
    col = mix(col, lum[:,None]*np.array([0.95,0.97,1.0]), rain*0.75)
    return col*1.15
W,H = 360, 140
up = np.array([0,1.0,0])
rows=[]
for sunUp in [0.9, 0.4, 0.15, 0.03, -0.08, -0.5]:
    sun = np.array([np.sqrt(1-sunUp*sunUp), sunUp, 0.0])
    yy, xx = np.mgrid[0:H, 0:W]
    az = (xx/W)*2*np.pi; el = (1-yy/H)*np.pi/2*0.98
    d = np.stack([np.cos(el)*np.cos(az), np.sin(el), np.cos(el)*np.sin(az)], -1).reshape(-1,3)
    c = aces(atmosphere(d, sun, up)).reshape(H,W,3)
    rows.append((c*255).astype(np.uint8))
Image.fromarray(np.concatenate(rows,0)).save(sys.argv[1])
