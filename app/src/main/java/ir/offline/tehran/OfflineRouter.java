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
    private static double distanceMeters(double lat1,double lon1,double lat2,double lon2) {
        double r=6371000.0;
        double p1=Math.toRadians(lat1),p2=Math.toRadians(lat2);
        double dp=Math.toRadians(lat2-lat1),dl=Math.toRadians(lon2-lon1);
        double h=Math.sin(dp/2)*Math.sin(dp/2)+Math.cos(p1)*Math.cos(p2)*Math.sin(dl/2)*Math.sin(dl/2);
        return 2*r*Math.atan2(Math.sqrt(h),Math.sqrt(Math.max(0,1-h)));
    }
    private Node nearest(double lat,double lon) {
        Node best=null; double bestDistance=Double.MAX_VALUE;
        for(Node n:nodes.values()) {
            double d=distanceMeters(lat,lon,n.lat,n.lon);
            if(d<bestDistance){bestDistance=d;best=n;}
        }
        return best;
    }
    public List<LatLong> route(double fromLat,double fromLon,Place destination) {
        Node startNode=nearest(fromLat,fromLon), goalNode=nearest(destination.lat,destination.lon);
        if(startNode==null || goalNode==null) return Collections.emptyList();
        // Never silently snap a start or destination to a distant, unrelated road.
        if(distanceMeters(fromLat,fromLon,startNode.lat,startNode.lon)>600 ||
           distanceMeters(destination.lat,destination.lon,goalNode.lat,goalNode.lon)>600)
            return Collections.emptyList();
        long start=startNode.id, goal=goalNode.id;
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
        // Keep the real GPS and selected destination as visible route endpoints.
        if(distanceMeters(fromLat,fromLon,path.get(0).latitude,path.get(0).longitude)>5)
            path.add(0,new LatLong(fromLat,fromLon));
        if(distanceMeters(destination.lat,destination.lon,path.get(path.size()-1).latitude,path.get(path.size()-1).longitude)>5)
            path.add(new LatLong(destination.lat,destination.lon));
        return path;
    }
    public void close(){db.close();}
}