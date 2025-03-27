package gov.nasa.jpl.viperws;

import gov.nasa.jpl.common.PostgresConnection;

import jakarta.json.*;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.mindrot.jbcrypt.BCrypt; // For password hashing

import java.io.InputStream;
import java.sql.*;

@Path("/sizing")
public class SizingEndPoint {
    @Path("/processGeometries")
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Response processGeometries() {
        // The query to process geometries and insert into rock_centers
        String processGeometriesSql =
                "WITH ValidGeometries AS (" +
                        "    SELECT" +
                        "        \"imageId\"," +
                        "        \"id\"," +
                        "        ST_Simplify(ST_MakeValid(\"drawing\"), 0.0001) AS valid_drawing" +
                        "    FROM" +
                        "        user_geometries" +
                        ")," +
                        "ClusteredRocks AS (" +
                        "    SELECT" +
                        "        \"imageId\"," +
                        "        ST_ClusterDBSCAN(valid_drawing, eps := 0.01, minpoints := 1) OVER(PARTITION BY \"imageId\") AS cluster_id," +
                        "        valid_drawing" +
                        "    FROM" +
                        "        ValidGeometries" +
                        ")," +
                        "MergedRocks AS (" +
                        "    SELECT" +
                        "        \"imageId\"," +
                        "        cluster_id," +
                        "        ST_Union(valid_drawing) AS merged_drawing" +
                        "    FROM" +
                        "        ClusteredRocks" +
                        "    GROUP BY" +
                        "        \"imageId\", cluster_id" +
                        ")," +
                        "RockCenter AS (" +
                        "    SELECT" +
                        "        \"imageId\"," +
                        "        ST_Centroid(merged_drawing) AS rock_center" +
                        "    FROM" +
                        "        MergedRocks" +
                        "    WHERE" +
                        "        cluster_id IS NOT NULL" +
                        ")" +
                        "INSERT INTO rock_centers (\"imageId\", \"location\")" +
                        "SELECT" +
                        "    \"imageId\"," +
                        "    rock_center" +
                        "FROM" +
                        "    RockCenter" +
                        "RETURNING \"imageId\";";

        try (Connection conn = PostgresConnection.getConnection()) {
            // Execute the query
            PreparedStatement stmt = conn.prepareStatement(processGeometriesSql);
            ResultSet rs = stmt.executeQuery();

            // Count the number of rows inserted
            int rowsInserted = 0;
            JsonArrayBuilder insertedImageIds = Json.createArrayBuilder();
            while (rs.next()) {
                rowsInserted++;
                insertedImageIds.add(rs.getInt("imageId"));
            }

            // Build the response
            JsonObjectBuilder responseJson = Json.createObjectBuilder();
            responseJson.add("message", "Geometries processed successfully");
            responseJson.add("rowsInserted", rowsInserted);
            responseJson.add("imageIds", insertedImageIds.build());

            return Response.ok(responseJson.build().toString()).build();
        } catch (SQLException e) {
            e.printStackTrace();
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(Json.createObjectBuilder()
                            .add("error", "Failed to process geometries: " + e.getMessage())
                            .build())
                    .build();
        }
    }
}
