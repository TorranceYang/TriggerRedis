import java.sql.*;
import java.util.HashMap;
import java.util.Date;

import org.postgresql.pljava.TriggerData;
import org.postgresql.pljava.annotation.Function;
import org.postgresql.pljava.annotation.SQLAction;
import org.postgresql.pljava.annotation.SQLActions;
import org.postgresql.pljava.annotation.Trigger;
import static org.postgresql.pljava.annotation.Trigger.Called.*;
import static org.postgresql.pljava.annotation.Trigger.Event.*;
import static org.postgresql.pljava.annotation.Function.Security.*;


import redis.clients.jedis.Jedis;  
import redis.clients.jedis.JedisPool;  
import redis.clients.jedis.exceptions.JedisException;  

public class TriggerRedis
{
	private static Jedis j = null;
	private static Connection c = null;
	private static String query = "SELECT farms.uuid, fc.farm_centroid, fc.farm_area, farms.created_at, farms.updated_at, farms.name, row_to_json(fc) as feature_collection FROM (SELECT 'FeatureCollection' AS type, feature.farm_uuid AS farm_uuid, ST_AsGeoJSON(ST_Centroid(ST_Collect(feature.geom)), 15, 2)::json AS farm_centroid, ST_Area(st_transform(ST_Collect(feature.geom), 4326)::geography) as farm_area, array_to_json(array_agg((SELECT f FROM (SELECT feature.type, feature.geometry, feature.properties) AS f))) AS features FROM (SELECT 'Feature'::text AS type, fields.farm_uuid, fields.geom, ST_AsGeoJSON(fields.geom, 15, 2)::json AS geometry, row_to_json((SELECT info FROM (SELECT ST_Area(st_transform(fields.geom, 4326)::geography) as area, ST_AsGeoJSON(ST_Centroid(fields.geom), 15, 2)::json as centroid, fields.uuid as uuid, fields.farm_uuid as farm_uuid, fields.updated_at as updated_at, fields.created_at as created_at, fields.properties as field_data) AS info)) AS properties FROM fields, farms where fields.farm_uuid = farms.uuid and farms.name = '";
	private static String query2 = "') AS feature GROUP BY feature.farm_uuid) AS fc RIGHT OUTER JOIN farms ON (fc.farm_uuid = farms.uuid) where farms.name = '";
	
	/**
	* Method for when a new farm is inserted. Assumes that there are no fields
	* already associated with the name. No exceptions implemented... yet
	*/
	@Function
	public static void insertFarm(TriggerData td) throws SQLException
	{
		ResultSet rs = td.getNew();
		HashMap<String, String> data = new HashMap<String, String>();
		createConnection();
		
		rs.next();
		String name = rs.getString("name");

		data.put("uuid", rs.getString("uuid"));
		data.put("farm_centroid", "");
		data.put("farm_area", "");
		data.put("created_at", rs.getString("created_at"));
		data.put("updated_at", rs.getString("updated_at"));
		data.put("name", name);
		data.put("feature_collection", "");

		j.hmset(name, data);
	}

	/**
	* Method for updating redis database upon inserting a new field. This assumes
	* that the farm already exists. Since generating the particular JSON output is 
	* rather difficult, this is just going to run the long query again
	*
	*/
	@Function
	public static void insertField(TriggerData td) throws SQLException
	{
		createDBConnection();
		createConnection();
		ResultSet rs = td.getNew();
		rs.next();

		//We could you TriggerData.getArguments() but we need a db connection anyways and it's not too much slower
		String farm_uuid = rs.getString("farm_uuid");
		ResultSet nameSet = c.createStatement().executeQuery("SELECT name FROM farms WHERE uuid='" + farm_uuid + "'");
		nameSet.next();
		String name = nameSet.getString("name");
		ResultSet rs2 = c.createStatement().executeQuery(query + name + query2 + name + "';");
		rs2.next();

		putCatch(name, "farm_centroid", rs2.getString("farm_centroid"));
		putCatch(name, "farm_area", rs2.getString("farm_area"));
		putCatch(name, "feature_collection", rs2.getString("feature_collection"));

		//Assuming that updating just one field also counts as updating the "farm"	
		String time = new Timestamp(new Date().getTime()).toString();
		c.createStatement().executeQuery("UPDATE farms SET updated_at='" + time + "' where name = '" + name + "'");
		putCatch(name, "updated_at", time); 	
	}

	@Function
	public static void updateFarm(TriggerData td) throws SQLException
	{
		if(td.isFiredByInsert() | td.isFiredByDelete())
			return;
		createConnection();
		ResultSet rsNew = td.getNew();
		ResultSet rsOld = td.getOld();
		rsNew.next();
		rsOld.next();
		String name = rsOld.getString("name");

		
		//Change the updated time before actually modifying info... incase the user wanted to change the updated time... for some reason
		//Redundant since this will be called again soon
		putCatch(name, "updated_at", new Timestamp(new Date().getTime()).toString());

		//There used to be a nice loop here with string comparision, but because it's possible to change the farm name, which is also they 
		//key in the redis server and also since you can possibly change the UUID as well, I have to implement a dumb brute force answer

		//What happens if you change the name
		if(!rsNew.getString("name").equals(rsOld.getString("name"))) 
		{
			j.rename(name, rsNew.getString("name"));
			name = rsNew.getString("name");
		}

		//What happens if you change the uuid; since you've gone and messed everything up, the redis "cache" will have to be refreshed with 
		//data from the new uuid which is done by requerying and re-mapping all of those values
		/*if(!rsNew.getString("uuid").equals(rsOld.getString("uuid"))) 
		{
			j.hset(name, "uuid", rsNew.getString("uuid"));
			createDBConnection();
			ResultSet rsUpdated = c.createStatement().executeQuery(query + name + query2 + name + "';");
			rsUpdated.next();
			j.hset(name, "farm_centroid", rsUpdated.getString("farm_centroid"));
			j.hset(name, "farm_area", rsUpdated.getString("farm_area"));
			j.hset(name, "feature_collection", rsUpdated.getString("feature_collection"));
		}
		*/

		//Actually no just kidding, there are multiple things you can decide to do when updating the uuid (like replacing the field farm_uuid)
		//so lets just say you CANT change the uuid to make things nice and simple

		if(!rsNew.getString("updated_at").equals(rsOld.getString("updated_at"))) 
		{
			putCatch(name, "updated_at", rsNew.getString("updated_at")); 	
		}

		putCatch(name, "created_at", rsNew.getString("created_at"));	
	}

	@Function
	public static void updateField(TriggerData td) throws SQLException
	{
		if(td.isFiredByInsert() | td.isFiredByDelete())
			return;
		createDBConnection();
		createConnection();
		ResultSet rsNew = td.getNew();
		ResultSet rsOld = td.getOld();
		rsNew.next();
		rsOld.next();

		String farm_uuid = rsNew.getString("farm_uuid");
		ResultSet nameSet = c.createStatement().executeQuery("SELECT name FROM farms WHERE uuid='" + farm_uuid + "'");
		nameSet.next();
		String name = nameSet.getString("name");
		ResultSet rs2 = c.createStatement().executeQuery(query + name + query2 + name + "';");
		rs2.next();

		putCatch(name, "farm_centroid", rs2.getString("farm_centroid"));
		putCatch(name, "farm_area", rs2.getString("farm_area"));
		putCatch(name, "feature_collection", rs2.getString("feature_collection"));
		
		String time = (new Timestamp(new Date().getTime())).toString();
		c.createStatement().executeQuery("UPDATE farms SET updated_at='" + time + "' where name = '" + name + "'");
		c.createStatement().executeQuery("UPDATE fields SET updated_at='" + time + "' where uuid = '" + rsOld.getString("uuid") + "'");
		putCatch(name, "updated_at", time); 	
	}

	@Function
	public static void deleteFarm(TriggerData td) throws SQLException
	{	
		createConnection();
		ResultSet rs = td.getOld();
		rs.next();

		j.del(rs.getString("name"));
	}

	@Function
	public static void deleteField(TriggerData td) throws SQLException
	{
		createDBConnection();
		createConnection();
		ResultSet rs = td.getOld();
		rs.next();

		String farm_uuid = rs.getString("farm_uuid");
		ResultSet nameSet = c.createStatement().executeQuery("SELECT name FROM farms WHERE uuid='" + farm_uuid + "'");
		nameSet.next();
		String name = nameSet.getString("name");
		ResultSet rs2 = c.createStatement().executeQuery(query + name + query2 + name + "';");
		rs2.next();

		putCatch(name, "farm_centroid", rs2.getString("farm_centroid"));
		putCatch(name, "farm_area", rs2.getString("farm_area"));
		putCatch(name, "feature_collection", rs2.getString("feature_collection"));

		String time = (new Timestamp(new Date().getTime())).toString();
		c.createStatement().executeQuery("UPDATE farms SET updated_at='" + time + "' where name = '" + name + "'");	
	}


	private static void createConnection()
	{
		j = new Jedis("localhost");
	}

	private static void createDBConnection()
	{
		try
		{
			c = DriverManager.getConnection("jdbc:default:connection");
		}
		catch(Exception e)
		{
			e.printStackTrace();
			System.err.println(e.getClass().getName() + ": " + e.getMessage());
		}
	}

	private static void putCatch(String key, String setKey, String setValue)
	{
		if(setValue == null)
		{
			try 
			{
				j.hset(key, setKey, "");
			}
			catch(Exception e)
			{
				e.printStackTrace();
				System.err.println(e.getClass().getName() + ": " + e.getMessage());
			}
		}
		else
		{
			j.hset(key, setKey, setValue);
		}
	}
}