package it.unibo.ep.demo;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.data.rest.core.annotation.RepositoryRestResource;

// Spring Data REST generates GET/POST/PUT/PATCH/DELETE on /people, plus /people/search/findBySurname
@RepositoryRestResource(collectionResourceRel = "people", path = "people")
public interface PersonRepository extends MongoRepository<Person, String> {
    List<Person> findBySurname(@Param("surname") String surname);
}
