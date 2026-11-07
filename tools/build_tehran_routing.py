#!/usr/bin/env python3
import osmium, sqlite3, math, os, sys

OUT = "app/src/main/assets/tehran-routing.db"
os.makedirs(os.path.dirname(OUT), exist_ok=True)
if os.path.exists(OUT): os.remove(OUT)
db = sqlite3.connect(OUT)
db.executescript("""
PRAGMA journal_mode=OFF;
PRAGMA synchronous=OFF;
PRAGMA temp_store=MEMORY;
CREATE TABLE nodes(id INTEGER PRIMARY KEY, lat REAL NOT NULL, lon REAL NOT NULL);
CREATE TABLE edges(src INTEGER NOT NULL, dst INTEGER NOT NULL, meters REAL NOT NULL, name TEXT);
CREATE INDEX edges_src ON edges(src);
CREATE TABLE places(name TEXT NOT NULL, lat REAL NOT NULL, lon REAL NOT NULL, node_id INTEGER NOT NULL);
CREATE INDEX places_name ON places(name);
""")
# Tehran and nearby cities: Tehran, Varamin, Pakdasht, Qarchak, Shahriar, Eslamshahr.
MIN_LON, MAX_LON, MIN_LAT, MAX_LAT = 50.45, 52.15, 35.05, 36.25
ALLOWED = {"motorway","trunk","primary","secondary","tertiary","unclassified",
           "residential","living_street","service","road","motorway_link",
           "trunk_link","primary_link","secondary_link","tertiary_link"}
def dist(a,b):
    r=6371000.0
    p1=math.radians(a[0]); p2=math.radians(b[0])
    dp=math.radians(b[0]-a[0]); dl=math.radians(b[1]-a[1])
    h=math.sin(dp/2)**2+math.cos(p1)*math.cos(p2)*math.sin(dl/2)**2
    return 2*r*math.asin(math.sqrt(h))
class Handler(osmium.SimpleHandler):
    def __init__(self):
        super().__init__(); self.nodes=set(); self.edges=[]; self.places=[]; self.ways=0
    def way(self,w):
        highway=w.tags.get("highway")
        if highway not in ALLOWED: return
        coords=[]
        try:
            for n in w.nodes:
                if not n.location.valid(): return
                lat,lon=n.location.lat,n.location.lon
                if not (MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON): return
                coords.append((n.ref,lat,lon))
        except Exception: return
        if len(coords)<2: return
        name=w.tags.get("name") or w.tags.get("name:fa") or w.tags.get("ref") or ""
        oneway=w.tags.get("oneway") in ("yes","1","true")
        reverse=w.tags.get("oneway")=="-1"
        for ref,lat,lon in coords: self.nodes.add((ref,lat,lon))
        for a,b in zip(coords,coords[1:]):
            d=dist((a[1],a[2]),(b[1],b[2]))
            if d<=0: continue
            if reverse: self.edges.append((b[0],a[0],d,name))
            else:
                self.edges.append((a[0],b[0],d,name))
                if not oneway: self.edges.append((b[0],a[0],d,name))
        if name:
            mid=coords[len(coords)//2]
            self.places.append((name,mid[1],mid[2],mid[0]))
        self.ways+=1
    def node(self,n):
        if not n.location.valid(): return
        lat,lon=n.location.lat,n.location.lon
        if not (MIN_LAT <= lat <= MAX_LAT and MIN_LON <= lon <= MAX_LON): return
        name=n.tags.get("name") or n.tags.get("name:fa") or ""
        if name and (n.tags.get("place") or n.tags.get("amenity") or n.tags.get("shop") or n.tags.get("public_transport")):
            self.places.append((name,lat,lon,n.id))
h=Handler()
h.apply_file(sys.argv[1], locations=True)
db.executemany("INSERT OR IGNORE INTO nodes VALUES(?,?,?)", h.nodes)
db.executemany("INSERT INTO edges VALUES(?,?,?,?)", h.edges)
db.executemany("INSERT INTO places VALUES(?,?,?,?)", h.places)
db.commit()
print("routing graph:",len(h.nodes),"nodes,",len(h.edges),"directed edges,",len(h.places),"search entries")
if not h.nodes or not h.edges or not h.places: raise SystemExit("OSM graph generation returned empty data")
