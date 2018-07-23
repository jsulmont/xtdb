(ns crux.lubm-test
  (:require [clojure.test :as t]
            [clojure.java.io :as io]
            [clojure.set :as set]
            [crux.db :as db]
            [crux.doc :as doc]
            [crux.index :as idx]
            [crux.tx :as tx]
            [crux.kv-store :as ks]
            [crux.rdf :as rdf]
            [crux.query :as q]
            [crux.kafka :as k]
            [crux.fixtures :as f]
            [crux.embedded-kafka :as ek])
  (:import [java.util Date]))

(t/use-fixtures :once ek/with-embedded-kafka-cluster)
(t/use-fixtures :each ek/with-kafka-client f/with-kv-store)

(defn load-ntriples-example [resource]
  (with-open [in (io/input-stream (io/resource resource))]
    (vec (for [entity (->> (rdf/ntriples-seq in)
                           (rdf/statements->maps))]
           [:crux.tx/put (:crux.db/id entity) entity]))))

;; See:
;; http://swat.cse.lehigh.edu/projects/lubm/
;; http://swat.cse.lehigh.edu/pubs/guo05a.pdf

;; The data is generated via
;; http://swat.cse.lehigh.edu/projects/lubm/uba1.7.zip and then
;; post-processed a bit via rdf4j into a single, sorted ntriples
;; document. The University0_0.ntriples file contains the subset from
;; http://swat.cse.lehigh.edu/projects/lubm/University0_0.owl

;; TODO: most queries need rules, some assume more departments loaded.
;; Full set is available in lubm/lubm10.ntriples, but query 2 does seem to
;; be either very slow or never return, a few others also quite slow,
;; but manageable. The full set is to large to submit in a single transaction.

;; Number of triples:
;; 8519 test/lubm/University0_0.ntriples
;; 100543 test/lubm/lubm10.ntriples

;; Total time, without query 2:
;; "Elapsed time: 4222.081773 msecs"
;; "Elapsed time: 72691.917908 msecs"
(t/deftest test-can-run-lubm-queries
  (let [tx-topic "test-can-run-lubm-queries"
        doc-topic "test-can-run-lubm-queries"
        tx-ops (->> (concat (load-ntriples-example "lubm/univ-bench.ntriples")
                            (load-ntriples-example "lubm/University0_0.ntriples"))
                    (map #(rdf/use-default-language % :en))
                    (vec))
        tx-log (k/->KafkaTxLog ek/*producer* tx-topic doc-topic)
        object-store (doc/new-cached-object-store f/*kv*)
        indexer (tx/->DocIndexer f/*kv* tx-log object-store)]

    (k/create-topic ek/*admin-client* tx-topic 1 1 k/tx-topic-config)
    (k/create-topic ek/*admin-client* doc-topic 1 1 k/doc-topic-config)
    (k/subscribe-from-stored-offsets indexer ek/*consumer* [tx-topic doc-topic])

    ;; "Elapsed time: 3541.99579 msecs"
    ;; "Elapsed time: 6142.151144 msecs"
    (t/testing "ensure data is indexed"
      (doseq [tx-ops (partition-all 1000 tx-ops)]
        @(db/submit-tx tx-log (vec tx-ops)))

      (k/consume-and-index-entities indexer ek/*consumer*)
      (while (not-empty (k/consume-and-index-entities indexer ek/*consumer* 100)))

      ;; "Elapsed time: 13.993752 msecs"
      ;; "Elapsed time: 0.602848 msecs"
      (t/testing "querying transacted data"
        (t/is (= #{[:http://www.University0.edu]}
                 (q/q (q/db f/*kv*) (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                      '{:find [u]
                                        :where [[u :ub/name "University0"]]}))))

        (t/testing "lazy result"
          (with-open [snapshot (doc/new-cached-snapshot (ks/new-snapshot f/*kv*) true)]
            (t/is (= '([:http://www.University0.edu])
                     (q/q snapshot (q/db f/*kv*) (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                                   '{:find [u]
                                                     :where [[u :ub/name "University0"]]}))))))))

    ;; This query bears large input and high selectivity. It queries about just one class and
    ;; one property and does not assume any hierarchy information or inference.
    ;; "Elapsed time: 7.174778 msecs"
    ;; "Elapsed time: 60.638423 msecs"
    (t/testing "LUBM query 1"
      (t/is (= #{[:http://www.Department0.University0.edu/GraduateStudent101]
                 [:http://www.Department0.University0.edu/GraduateStudent124]
                 [:http://www.Department0.University0.edu/GraduateStudent142]
                 [:http://www.Department0.University0.edu/GraduateStudent44]}
               (q/q (q/db f/*kv*)
                    (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                      '{:find [x]
                        :where [[x :rdf/type :ub/GraduateStudent]
                                [x :ub/takesCourse :http://www.Department0.University0.edu/GraduateCourse0]]})))))

    ;; TODO: subOrganizationOf is transitive, should use rules.

    ;; This query increases in complexity: 3 classes and 3 properties are involved. Additionally,
    ;; there is a triangular pattern of relationships between the objects involved.
    ;; "Elapsed time: 1833.664714 msecs"
    ;; DNF
    (t/testing "LUBM query 2"
      (t/is (empty? (q/q (q/db f/*kv*)
                         (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                           '{:find [x y z]
                             :where [[x :rdf/type :ub/GraduateStudent]
                                     [y :rdf/type :ub/University]
                                     [z :rdf/type :ub/Department]
                                     [x :ub/memberOf z]
                                     [z :ub/subOrganizationOf y]
                                     [x :ub/undergraduateDegreeFrom y]]})))))

    ;; TODO: Publication has subClassOf children, should use rules.

    ;; This query is similar to Query 1 but class Publication has a wide hierarchy.
    ;; "Elapsed time: 18.763819 msecs"
    ;; "Elapsed time: 149.333853 msecs"
    (t/testing "LUBM query 3"
      (t/is (= #{[:http://www.Department0.University0.edu/AssistantProfessor0/Publication0]
                 [:http://www.Department0.University0.edu/AssistantProfessor0/Publication1]
                 [:http://www.Department0.University0.edu/AssistantProfessor0/Publication2]
                 [:http://www.Department0.University0.edu/AssistantProfessor0/Publication3]
                 [:http://www.Department0.University0.edu/AssistantProfessor0/Publication4]
                 [:http://www.Department0.University0.edu/AssistantProfessor0/Publication5]}
               (q/q (q/db f/*kv*)
                    (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                      '{:find [x]
                        :where [ ;; [x :rdf/type :ub/Publication]
                                (or [x :rdf/type :ub/Publication]
                                    [x :rdf/type :ub/Article]
                                    [x :rdf/type :ub/ConferencePaper]
                                    [x :rdf/type :ub/JournalArticle]
                                    [x :rdf/type :ub/TechReport]
                                    [x :rdf/type :ub/Book]
                                    [x :rdf/type :ub/Manual]
                                    [x :rdf/type :ub/Software]
                                    [x :rdf/type :ub/Specification]
                                    [x :rdf/type :ub/UnofficialPublication])
                                [x :ub/publicationAuthor :http://www.Department0.University0.edu/AssistantProfessor0]]})))))

    ;; TODO: AssociateProfessor should be Professor.
    ;; Should return 35 with lubm10.ntriples.

    ;; This query has small input and high selectivity. It assumes subClassOf relationship
    ;; between Professor and its subclasses. Class Professor has a wide hierarchy. Another
    ;; feature is that it queries about multiple properties of a single class.
    ;; "Elapsed time: 3.680617 msecs"
    ;; "Elapsed time: 8.05811 msecs"
    (t/testing "LUBM query 4"
      (let [result (q/q (q/db f/*kv*)
                        (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                          '{:find [x y1 y2 y3]
                            :where [ ;; [x :rdf/type :ub/Professor]
                                    (or [x :rdf/type :ub/Professor]
                                        [x :rdf/type :ub/AssistantProfessor]
                                        [x :rdf/type :ub/AssociateProfessor]
                                        [x :rdf/type :ub/Chair]
                                        [x :rdf/type :ub/Dean]
                                        [x :rdf/type :ub/FullProfessor]
                                        [x :rdf/type :ub/VisitingProfessor])
                                    [x :ub/worksFor :http://www.Department0.University0.edu]
                                    [x :ub/name y1]
                                    [x :ub/emailAddress y2]
                                    [x :ub/telephone y3]]}))]
        (t/is (= 34 (count result)))
        (t/is (contains? result [:http://www.Department0.University0.edu/AssistantProfessor0
                                 "AssistantProfessor0"
                                 "AssistantProfessor0@Department0.University0.edu"
                                 "xxx-xxx-xxxx"]))))

    ;; TODO: This should really us rules for subClassOf and
    ;; subPropertyOf.

    ;; This query assumes subClassOf relationship between Person and its subclasses
    ;; and subPropertyOf relationship between memberOf and its subproperties.
    ;; Moreover, class Person features a deep and wide hierarchy.
    (t/testing "LUBM query 5"
      (t/is (= 719 (count (q/q (q/db f/*kv*)
                               (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                 '{:find [x]
                                   :where [ ;; [x :rdf/type :ub/Person]
                                           (or [x :rdf/type :ub/Person]
                                               [x :rdf/type :ub/Employee]
                                               [x :rdf/type :ub/AdministrativeStaff]
                                               [x :rdf/type :ub/Faculty]
                                               [x :rdf/type :ub/PostDoc]
                                               [x :rdf/type :ub/Lecturer]
                                               [x :rdf/type :ub/Professor]
                                               [x :rdf/type :ub/AssistantProfessor]
                                               [x :rdf/type :ub/AssociateProfessor]
                                               [x :rdf/type :ub/Chair]
                                               [x :rdf/type :ub/Dean]
                                               [x :rdf/type :ub/FullProfessor]
                                               [x :rdf/type :ub/VisitingProfessor]
                                               [x :rdf/type :ub/Student]
                                               [x :rdf/type :ub/UndergraduateStudent]
                                               [x :rdf/type :ub/GraduateStudent]
                                               [x :rdf/type :ub/Director]
                                               [x :rdf/type :ub/TeachingAssistant]
                                               [x :rdf/type :ub/ResearchAssistant])
                                           ;; [x :ub/memberOf :http://www.Department0.University0.edu]
                                           (or [x :ub/memberOf :http://www.Department0.University0.edu]
                                               [x :ub/worksFor :http://www.Department0.University0.edu])]}))))))

    ;; TODO: Should use rules. Should return 7790 with lubm10.ntriples.

    ;; This query queries about only one class. But it assumes both the explicit
    ;; subClassOf relationship between UndergraduateStudent and Student and the
    ;; implicit one between GraduateStudent and Student. In addition, it has large
    ;; input and low selectivity.
    (t/testing "LUBM query 6"
      (t/is (= 678 (count (q/q (q/db f/*kv*)
                               (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                 '{:find [x]
                                   :where [ ;; [x :rdf/type :ub/Student]
                                           (or [x :rdf/type :ub/Student]
                                               [x :rdf/type :ub/UndergraduateStudent]
                                               [x :rdf/type :ub/GraduateStudent])]}))))))

    ;; TODO: Should use rules.
    ;; Should return 110 with lubm10.ntriples (is this for UndergraduateStudent?).
    ;; EmptyHeaded returns 59 for this with UndergraduateStudent.

    ;; This query is similar to Query 6 in terms of class Student but it increases in the
    ;; number of classes and properties and its selectivity is high.
    ;; "Elapsed time: 480.39002 msecs"
    ;; "Elapsed time: 65851.740685 msecs"
    (t/testing "LUBM query 7"
      (t/is (= 67 (count (q/q (q/db f/*kv*)
                              (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                '{:find [x y]
                                  :where [ ;; [x :rdf/type :ub/Student]
                                          (or [x :rdf/type :ub/Student]
                                              [x :rdf/type :ub/UndergraduateStudent]
                                              [x :rdf/type :ub/GraduateStudent])
                                          ;; [y :rdf/type :ub/Course]
                                          (or [y :rdf/type :ub/Course]
                                              [y :rdf/type :ub/GraduateCourse])
                                          [x :ub/takesCourse y]
                                          [:http://www.Department0.University0.edu/AssociateProfessor0
                                           :ub/teacherOf
                                           y]]})))))

      (t/testing "literal entity on its own"
        (t/is (= 4 (count (q/q (q/db f/*kv*)
                               (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                 '{:find [y]
                                   :where [[:http://www.Department0.University0.edu/AssociateProfessor0
                                            :ub/teacherOf
                                            y]]})))))))

    ;; TODO: Should use rules. Cannot use or for memberOf/worksFor here.
    ;; Should return 7791 with lubm10.ntriples (is this for UndergraduateStudent?).
    ;; EmptyHeaded returns 5916 for this with UndergraduateStudent.

    ;; This query is further more complex than Query 7 by including one more property.
    ;; "Elapsed time: 40.463253 msecs"
    ;; "Elapsed time: 1465.576616 msecs"

    ;; TODO: Slower with [z y] join order using dependency, should
    ;; figure out how to make y move forward.
    (t/testing "LUBM query 8"
      (t/is (= 678 (count (q/q (q/db f/*kv*)
                               (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                 '{:find [x y z]
                                   :where [ ;; [x :rdf/type :ub/Student]
                                           (or [x :rdf/type :ub/Student]
                                               [x :rdf/type :ub/UndergraduateStudent]
                                               [x :rdf/type :ub/GraduateStudent])
                                           [y :rdf/type :ub/Department]
                                           [x :ub/memberOf y]
                                           [y :ub/subOrganizationOf :http://www.University0.edu]
                                           [x :ub/emailAddress z]]}))))))

    ;; TODO: Should use rules.

    ;; Besides the aforementioned features of class Student and the wide hierarchy of
    ;; class Faculty, like Query 2, this query is characterized by the most classes and
    ;; properties in the query set and there is a triangular pattern of relationships.
    (t/testing "LUBM query 9"
      (t/is (= 13 (count (q/q (q/db f/*kv*)
                              (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                '{:find [x y z]
                                  :where [ ;; [x :rdf/type :ub/Student]
                                          (or [x :rdf/type :ub/Student]
                                              [x :rdf/type :ub/UndergraduateStudent]
                                              [x :rdf/type :ub/GraduateStudent])
                                          ;; [y :rdf/type :ub/Faculty]
                                          (or [y :rdf/type :ub/Faculty]
                                              [y :rdf/type :ub/PostDoc]
                                              [y :rdf/type :ub/Lecturer]
                                              [y :rdf/type :ub/Professor]
                                              [y :rdf/type :ub/AssistantProfessor]
                                              [y :rdf/type :ub/AssociateProfessor]
                                              [y :rdf/type :ub/Chair]
                                              [y :rdf/type :ub/Dean]
                                              [y :rdf/type :ub/FullProfessor]
                                              [y :rdf/type :ub/VisitingProfessor])
                                          ;; [z :rdf/type :ub/Course]
                                          (or [z :rdf/type :ub/Course]
                                              [z :rdf/type :ub/GraduateCourse])
                                          [x :ub/advisor y]
                                          [y :ub/teacherOf z]
                                          [x :ub/takesCourse z]]}))))))

    ;; TODO: Should use rules.

    ;; This query differs from Query 6, 7, 8 and 9 in that it only requires the
    ;; (implicit) subClassOf relationship between GraduateStudent and Student, i.e.,
    ;; subClassOf rela-tionship between UndergraduateStudent and Student does not add
    ;; to the results.
    (t/testing "LUBM query 10"
      (t/is (= 4 (count (q/q (q/db f/*kv*)
                             (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                               '{:find [x]
                                 :where [ ;; [x :rdf/type :ub/Student]
                                         (or [x :rdf/type :ub/Student]
                                             [x :rdf/type :ub/UndergraduateStudent]
                                             [x :rdf/type :ub/GraduateStudent])
                                         [x :ub/takesCourse :http://www.Department0.University0.edu/GraduateCourse0]]}))))))

    ;; TODO: should use transitive rule.

    ;; Query 11, 12 and 13 are intended to verify the presence of certain OWL reasoning
    ;; capabilities in the system. In this query, property subOrganizationOf is defined
    ;; as transitive. Since in the benchmark data, instances of ResearchGroup are stated
    ;; as a sub-organization of a Department individual and the later suborganization of
    ;; a University individual, inference about the subOrgnizationOf relationship between
    ;; instances of ResearchGroup and University is required to answer this query.
    ;; Additionally, its input is small.
    (t/testing "LUBM query 11"
      (t/is (= 10 (count (q/q (q/db f/*kv*)
                              (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                '{:find [x]
                                  :where [[x :rdf/type :ub/ResearchGroup]
                                          [x :ub/subOrganizationOf d]
                                          [d :rdf/type :ub/Department]
                                          ;; [x :ub/subOrganizationOf :http://www.University0.edu]
                                          [d :ub/subOrganizationOf :http://www.University0.edu]]}))))))

    ;; TODO: FullProfessor should really be Chair.
    ;; Should return 15 with lubm10.ntriples.
    ;; EmptyHeaded returns 125 for this with FullProfessor.

    ;; The benchmark data do not produce any instances of class Chair. Instead, each
    ;; Department individual is linked to the chair professor of that department by
    ;; property headOf. Hence this query requires realization, i.e., inference that
    ;; that professor is an instance of class Chair because he or she is the head of a
    ;; department. Input of this query is small as well.
    ;; "Elapsed time: 1.375493 msecs"
    ;; "Elapsed time: 37.883395 msecs"
    (t/testing "LUBM query 12"
      (t/is (= 10 (count (q/q (q/db f/*kv*)
                              (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                '{:find [x y]
                                  :where [[x :rdf/type :ub/FullProfessor]
                                          [y :rdf/type :ub/Department]
                                          [x :ub/worksFor y]
                                          [y :ub/subOrganizationOf :http://www.University0.edu]]})))))

      ;; TODO: actual result, should use rules.
      (t/is (= 1 (count (q/q (q/db f/*kv*)
                             (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                               '{:find [x y]
                                 :where [[y :rdf/type :ub/Department]
                                         [x :ub/headOf y]
                                         [y :ub/subOrganizationOf :http://www.University0.edu]]}))))))

    ;; TODO: should use rules.

    ;; Property hasAlumnus is defined in the benchmark ontology as the inverse of
    ;; property degreeFrom, which has three subproperties: undergraduateDegreeFrom,
    ;; mastersDegreeFrom, and doctoralDegreeFrom. The benchmark data state a person as
    ;; an alumnus of a university using one of these three subproperties instead of
    ;; hasAlumnus. Therefore, this query assumes subPropertyOf relationships between
    ;; degreeFrom and its subproperties, and also requires inference about inverseOf.
    (t/testing "LUBM query 13"
      (t/is (= #{[:http://www.Department0.University0.edu/AssistantProfessor2]}
               (q/q (q/db f/*kv*)
                    (rdf/with-prefix {:rdf "http://www.w3.org/1999/02/22-rdf-syntax-ns#"
                                      :ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                      '{:find [x]
                        :where [ ;; [x :rdf/type :ub/Person]
                                (or [x :rdf/type :ub/Person]
                                    [x :rdf/type :ub/Employee]
                                    [x :rdf/type :ub/AdministrativeStaff]
                                    [x :rdf/type :ub/Faculty]
                                    [x :rdf/type :ub/PostDoc]
                                    [x :rdf/type :ub/Lecturer]
                                    [x :rdf/type :ub/Professor]
                                    [x :rdf/type :ub/AssistantProfessor]
                                    [x :rdf/type :ub/AssociateProfessor]
                                    [x :rdf/type :ub/Chair]
                                    [x :rdf/type :ub/Dean]
                                    [x :rdf/type :ub/FullProfessor]
                                    [x :rdf/type :ub/VisitingProfessor]
                                    [x :rdf/type :ub/Student]
                                    [x :rdf/type :ub/UndergraduateStudent]
                                    [x :rdf/type :ub/GraduateStudent]
                                    [x :rdf/type :ub/Director]
                                    [x :rdf/type :ub/TeachingAssistant]
                                    [x :rdf/type :ub/ResearchAssistant])
                                ;; [:http://www.University0.edu :ub/hasAlumnus x]
                                (or [x :ub/undergraduateDegreeFrom :http://www.University0.edu]
                                    [x :ub/mastersDegreeFrom :http://www.University0.edu]
                                    [x :ub/doctoralDegreeFrom :http://www.University0.edu])]})))))

    ;; TODO: Should return 5916 with lubm10.ntriples, which we do.

    ;; This query is the simplest in the test set. This query
    ;; represents those with large input and low selectivity and does
    ;; not assume any hierarchy information or inference.
    ;; "Elapsed time: 7.821926 msecs"
    ;; "Elapsed time: 83.661817 msecs"
    (t/testing "LUBM query 14"
      (t/is (= 532 (count (q/q (q/db f/*kv*)
                               (rdf/with-prefix {:ub "http://swat.cse.lehigh.edu/onto/univ-bench.owl#"}
                                 '{:find [x]
                                   :where [[x :rdf/type :ub/UndergraduateStudent]]}))))))))
