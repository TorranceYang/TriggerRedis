# TriggerRedis

This is a small project/challenge I've been working on given to me by a startup called FarmShots. It essentially serves a simple puesdo web cache for data pertaining to farms, namely, the collection of fields which contains crop information, field area, and geometry calculations. 

The challenge was to improve the speed of the query, which can be roughly deciphered within TriggerRedis.java, and this particlar project was built using a PostgreSQL database (9.5.2) and a redis database (3.0.6) serving as a cache, all locally hosted. In order to use Java to write functions able to handle the communication between the psql database and the redis cache, I used [Jedis](https://github.com/xetorthio/jedis), a Java client for Redis, and an add-on module for psql called [Pl/Java](https://github.com/tada/pljava). This project was built using [Maven](https://maven.apache.org/)

# Background

First off, there are two tables located within the database, one called ***farms*** and the other called ***fields***. The ***farms*** table includes 4 different columns, namely, **uuid, created_at, updated_at** as well as **farm name**. Within ***fields*** we have **uuid, created_at, updated_at, farm_uuid, geom,** and **properties**. There's also a column called "name" but it's never used so I just ignored it. The *farm_uuid* field HAS to point to a valid existing farm, and the geometry + property fields must be valid as well.

However, the query does not ask for just this data. For each farm it requests **uuid, created_at, updated_at, farm_area, farm_centroid, name,** and **feature_collection**. The new columns are calculated by the farm's collection of field data. The redis cache (a key-value database) uses the farm's name as the key, and a hashet as the value. The hashset itself contains another key pair with the key being the columns previoulsy mentioned, and the value being... just the value.

# How it works

Basically PL/Java lets you create triggers within the database that allows you to call java functions. I have included 6 different triggers (3 different trigger types for 2 tables) which do slightly different things. 

1. Insert Farm
  * Create new key
  * Does no other query or calculations, assumes no fields exist for it yet
2. Insert Field
  * Re-runs query
  * Updates cache values
  * Updates farm.updated_at value 
    * As we assume inserting a field counts as updating the farm
3. Update Farm
  * Updates cache values
    * If the farm name is changed, so is the key
    * Does not allow the uuid to be changed
4. Update Field
  * Re-runs query
  * Updates cache values
  * Updates farm.updated_at value 
  * Updates its own updated_at value
5. Delete Farm
  * Removes key from the cache
6. Delete Field
  * Re-runs query
  * Updates cache values
  * Updates farm.updated_at value 
  
# Comments

Jedis was fundamentally easy to use, but PL/Java was far more difficult to use. It took literally days to figure out how to build the project successfully, write certain functions, and modify the database. While a very powerful addon, it was challenging to get started using it to its full potential wihtout prior knowledge of this subject area and solid documentation. 

For instance, in order to install a java file, it was *implied* that all you need to do is:

```sql
SELECT sqlj.install_jar('pathtojar.jar', 'jarName', true);
SELECT sqlj.set_classpath('public', 'jarName');
```

But afterwards, you still need to hand install the function like so: 

```sql
CREATE OR REPLACE FUNCTION myFunction() 
  RETURNS TRIGGER AS 'ClassName.myFunction' 
  LANGUAGE java;
```

The PL\Java downloading instructions, only now that I look back on it, are thorough, but the correct answers on how to install it is obfuscated and hidden under layers of sub web pages. Don't know how I messed everything up, but I managed to break my PATH variable entirely sometime over this project completion and had to completely reinstall Ubunut onto my laptop. There were also a few conflicts during the maven clean install that I think I fixed by changing versions of my redis cache. Thankfully, Jedis can be used as a maven dependency. 

There were also some conflicts I ran into in the Java code. I didn't realize that inserting into a database not only calls the 'insert' trigger, but also calls the 'update' trigger. I had to account for that in the code. I also overlooked the fact, that by requerying and updating the **updated_at** field, I was also create a recursive trigger call which I managed to fix by installing triggers this way:

```sql
CREATE TRIGGER myTrigger AFTER DELETE ON table 
FOR EACH ROW 
WHEN (pg_trigger_depth() = 0) 
EXECUTE PROCEDURE myFunction();
```
Only allowing the update trigger to call once per initial call fixed the issue. 

