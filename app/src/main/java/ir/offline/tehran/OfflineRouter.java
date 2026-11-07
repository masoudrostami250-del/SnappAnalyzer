package ir.offline.tehran;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import org.mapsforge.core.model.LatLong;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.*;

public class OfflineRouter {
    public static class Place {
        public final String name; public final double lat, lon; public final long node;
        Place(String n,double a,double o,long id){name=n;lat=a;lon=o;node=id;}
        @Override public String toString(){return name;}
    }
    private static class Node {
        long id; double lat,lon;
        Node(long i,double a,double o){id=i;lat=a;lon=o;}
    }
    private static class Edge {
        long to; double cost;
        Edge(long t,double c){to=t;cost=c;}
    }
    private final SQLiteDatabase db;
    private final HashMap<Long,Node> nodes=new HashMap<>();
    private final HashMap<Long,ArrayList<Edge>> graph=new HashMap<>();

    public OfflineRouter(Context context) throws Exception {
        File file=new File(context.getFilesDir(),"tehran-routing.db");
        if(!file.exists() || file.length()==0){
            try(InputStream in=context.getAssets().open("tehran-routing.db");
                FileOutputStream out=new FileOutputStream(file)){
                byte[] b=new byte[1024*1024]; int n;
                while((n=in.read(b))!=-1) out.write(b,0,n);
            }
        }
        db=SQLiteDatabase.openDatabase(file.getAbsolutePath(),null,SQLiteDatabase.OPEN_READONLY);
        try(Cursor c=db.rawQuery("SELECT id,lat,lon FROM nodes",null)){
            while(c.moveToNext()) nodes.put(c.getLong(0),new Node(c.getLong(0),c.getDouble(1),c.getDouble(2)));
        }
        try(Cursor c=db.rawQuery("SELECT src,dst,meters FROM edges",null)){
            while(c.moveToNext()){
                long src=c.getLong(0),dst=c.getLong(1);
                ArrayList<Edge> list=graph.get(src);
                if(list==null){list=new ArrayList<>();graph.put(src,list);}
                list.add(new Edge(dst,c.getDouble(2)));
            }
        }
    }
    public List<Place> search(String query,int limit){
        ArrayList<Place> out=new ArrayList<>();
        String q=query.trim();
        if(q.isEmpty()) return out;
        try(Cursor c=db.rawQuery("SELECT name,lat,lon,node_id FROM places WHERE name LIKE ? COLLATE NOCASE GROUP BY name,node_id LIMIT ?",
                new String[]{"%"+q+"%",String.valueOf(limit)})){
            while(c.moveToNext()) out.add(new Place(c.getString(0),c.getDouble(1),c.getDouble(2),c.getLong(3)));
        }
        return out;
    }
    private long nearest(double lat,double lon){
        long best=-1; double score=Double.MAX_VALUE;
        for(Node n:nodes.values()){
            double dy=(n.lat-lat)*111000.0, dx=(n.lon-lon)*90000.0;
            double s=dx*dx+dy*dy;
            if(s<score){score=s;best=n.id;}
        }
        return best;
    }
    public List<LatLong> route(double fromLat,double fromLon,Place destination) {
        long start=nearest(fromLat,fromLon), goal=destination.node;
        if(start<0 || !nodes.containsKey(goal)) return Collections.emptyList();
        HashMap<Long,Double> distances=new HashMap<>();
        HashMap<Long,Long> previous=new HashMap<>();
        PriorityQueue<long[]> queue=new PriorityQueue<>(Comparator.comparingDouble(a->Double.longBitsToDouble(a[1])));
        distances.put(start,0.0); queue.add(new long[]{start,Double.doubleToRawLongBits(0.0)});
        while(!queue.isEmpty()){
            long[] item=queue.poll(); long id=item[0];
            double d=Double.longBitsToDouble(item[1]);
            if(d>distances.getOrDefault(id,Double.MAX_VALUE)) continue;
            if(id==goal) break;
            for(Edge e:graph.getOrDefault(id,new ArrayList<Edge>())){
                double nd=d+e.cost;
                if(nd<distances.getOrDefault(e.to,Double.MAX_VALUE)){
                    distances.put(e.to,nd); previous.put(e.to,id);
                    queue.add(new long[]{e.to,Double.doubleToRawLongBits(nd)});
                }
            }
        }
        if(!distances.containsKey(goal)) return Collections.emptyList();
        ArrayList<LatLong> path=new ArrayList<>();
        long cur=goal;
        while(true){
            Node n=nodes.get(cur); if(n==null) return Collections.emptyList();
            path.add(new LatLong(n.lat,n.lon));
            if(cur==start) break;
            Long p=previous.get(cur); if(p==null) return Collections.emptyList(); cur=p;
        }
        Collections.reverse(path);
        return path;
    }
    public void close(){db.close();}
}