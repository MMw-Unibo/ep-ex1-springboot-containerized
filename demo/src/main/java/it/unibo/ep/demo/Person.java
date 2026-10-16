package it.unibo.ep.demo;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document("people")
public class Person {
    @Id
    private String id;
    private String name;
    private String surname;
    private int age;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSurname() { return surname; }
    public void setSurname(String surname) { this.surname = surname; }
    public int getAge() { return age; }
    public void setAge(int age) { this.age = age; }
}
