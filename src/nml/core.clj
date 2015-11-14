(ns nml.core
  (:require [clojure.string    :as string])
  (:require [instaparse.core   :as insta ])
  (:require [clojure.tools.cli :as cli   ])
  (:gen-class))

(declare nml-get nml-name nml-parse nml-set nml-str nml-uniq strmap)

;; formatting

(defn- fmt-sh [m lo]
  (let [ms  (sort m)
        n   (lo 1)
        k   (lo 2)
        eq #(string/replace % "\"" "\\\"")
        f0  (fn [[dataref values]]
              (let [v (eq values)]
                (str "'" dataref "') echo \"" v "\";;")))
        f1  (fn [[name nvseq]]
              (let [x (strmap f0 nvseq)]
                (str "'" name "') case " k " in " x "*) echo '';;esac;;" )))]
    (str "nmlquery(){ case " n " in " (strmap f1 ms) "*) echo '';;esac; }\n")))

(defn- fmt-bash [m]
  (fmt-sh m #(str "\"${" % ",,}\"")))

(defn- fmt-ksh [m]
  (fmt-sh m #(str "\"$(echo $" % " | tr [:upper:] [:lower:])\"")))

(defn- fmt-namelist [m]
  (let [f0 (fn [[dataref values]]
             (str "  " dataref "=" values "\n"))
        f1 (fn [[name nvseq]]
             (str "&" name "\n" (strmap f0 (sort nvseq)) "/\n"))]
    (strmap f1 (sort m))))

;; defs

(def formats
  {"bash"     fmt-bash
   "ksh"      fmt-ksh
   "namelist" fmt-namelist})

(def msgs
  {:bad-format    "Bad output format"
   :edit+in       "-i/--in not valid with -e/--edit"
   :edit+out      "-o/--out not valid with -e/--edit"
   :get+edit      "-e/--edit not valid with -g/--get"
   :get+format    "-f/--format not valid with -g/--get"
   :get+set       "-g/--get and -s/--set may not be mixed"
   :multi-edit    "-e/--edit may be specified only once"
   :multi-format  "-f/--format may be specified only once"
   :multi-in      "-i/--in may be specified only once"
   :multi-out     "-o/--out may be specified only once"
   :set+no-prefix "-n/--no-prefix not valid with -s/--set"})

(def parse (insta/parser (clojure.java.io/resource "grammar")))

(def version "0.3")

;; utility defns

(defn- fail [& lines]
  (binding [*out* *err*]
    (doseq [line lines]
      (println (str "nml: " line)))
    (System/exit 1)))

(defn- read-file [in]
  (try (slurp in)
       (catch Exception e
         (fail (str "Could not read from '" in "'")))))

(defn- strmap [f coll]
  (apply str (map f coll)))

(defn- usage [summary]
  (let [f (str "Valid output formats are: " (string/join ", " (keys formats)))]
    (doseq [x ["\nUsage: nml [options]\n\nOptions:\n" summary "" f ""]]
      (println x))
    (System/exit 0)))

;; nml private defns

(defn- nml-gets [m gets no-prefix]
  (let [f (fn [[nml key]]
            (let [val (nml-get m nml key)]
              (if (= "" val) (fail (str nml ":" key " not found")))
              (if no-prefix val (str nml ":" key "=" val))))]
    (str (string/join "\n" (map f gets)) "\n")))

(defn- nml-name [x]
  (nml-str (second x)))

(defn- nml-out [out s]
  (if (= out *out*)
    (println (string/trim s))
    (try (spit out s)
         (catch Exception e
           (fail (str "Could not write to '" out "'"))))))

(defn- nml-parse [s start src]
  (let [result (parse s :start start :optimize :memory)]
;;   (let [parses (insta/parses parse s)]
;;     (binding [*out* *err*]
;;       (println (count parses))
;;       (doseq [parse parses]
;;         (println parse)
;;         (println))))
    (if (insta/failure? result)
      (let [{t :text l :line c :column} result]
        (fail (str "Error parsing " src " at line " l " column " c ":")
              t
              (str (apply str (repeat (- c 1) " ")) "^")))
      result)))

(defn- nml-sets [m sets]
  (loop [m m s sets]
    (if (empty? s)
      m
      (let [[nml key val] (first s)]
        (if (nil? val) (fail (str "No value supplied for key '" key "'")))
        (recur (nml-set m nml key val) (rest s))))))

(defn- nml-str [x]
  (let [key       (first x)
        val       (rest  x)
        cjoin    #(string/join "," %)
        delegate #(map nml-str %)
        delesort  (fn [val] (delegate (sort-by #(nml-name %) val)))
        list2str #(strmap nml-str %)
        strfirst #(nml-str (first %))
        strlast  #(nml-str (last %))]
    (apply str (case key
                 :s        (string/join "\n" (delesort val))
                 :array    [(strfirst val) (strlast val)]
                 :c        (strfirst val)
                 :comment  ""
                 :complex  ["(" (cjoin (delegate val)) ")"]
                 :dataref  (delegate val)
                 :dec      ["." (strlast val)]
                 :exp      [(first val) (strlast val)]
                 :false    "f"
                 :int      (delegate val)
                 :junk     ""
                 :logical  (strfirst val)
                 :name     (map string/lower-case val)
                 :nvseq    (string/join " " (delesort val))
                 :nvsubseq [(strfirst val) "=" (strlast val)]
                 :partref  (strfirst val)
                 :r        (strfirst val)
                 :real     (delegate val)
                 :sect     ["(" (list2str val) ")"]
                 :sep      val
                 :sign     val
                 :slash    val
                 :star     "*"
                 :stmt     ["&" (strfirst val) " " (list2str (rest val)) " /"]
                 :string   val
                 :true     "t"
                 :uint     val
                 :valopt   (cjoin (delegate val))
                 :value    (delegate val)
                 :values   (cjoin (delegate val))
                 :ws       ""
                 :wsopt    ""
                 val))))

(defn- nml-tree [s src]
  (let [tree  (nml-parse s :s src)
        child :nvseq
        f     (fn [& v] (into [child] (nml-uniq v)))]
    (insta/transform {child f} tree)))

(defn- nml-uniq [values]
  (loop [head (first values) tail (rest values) tree []]
    (if (nil? head)
      tree
      (let [name #(nml-name (second %))
            copy  (some #(= (name head) (name %)) tail)]
        (recur (first tail) (rest tail) (into tree (if copy [] [head])))))))

;; nml public defns

(defn nml-get [m nml key]
  (get (get m (string/lower-case nml) {}) (string/lower-case key) ""))

(defn nml-map [s src]
  (let [f0   (fn [& nvsubseqs   ] (into {} nvsubseqs))
        f1   (fn [dataref values] { (nml-str dataref) (nml-str values) })
        f2   (fn [& stmts       ] (into {} stmts))
        f3   (fn [name & nvseq  ] { (nml-str name) (first nvseq) })
        tree (nml-tree s src)]
    (insta/transform {:nvseq f0 :nvsubseq f1 :s f2 :stmt f3 } tree)))

(defn nml-set [m nml key val]
  (let [src (str "user-supplied value")
        val (nml-str (nml-parse val :values src))]
    (assoc-in m [(string/lower-case nml) (string/lower-case key)] val)))

;; cli

(defn- assoc-e [m k v]
  (if (k m) (fail (msgs :multi-edit)))
  (assoc m k v))

(defn- assoc-f [m k v]
  (if (k m) (fail (msgs :multi-format)))
  (assoc m k v))

(defn- assoc-g [m k v]
  (let [gets (:get m [])]
    (assoc m :get (into gets [v]))))

(defn- assoc-i [m k v]
  (if (k m) (fail (msgs :multi-in)))
  (assoc m k v))

(defn- assoc-o [m k v]
  (if (k m) (fail (msgs :multi-out)))
  (assoc m k v))

(defn- assoc-s [m k v]
  (let [sets (:set m [])]
    (assoc m :set (into sets [v]))))

(defn- parse-f [x]
  (if-not (contains? formats x) (fail (msgs :bad-format)))
  x)

(defn- parse-g [x]
  (string/split x #":" 2))

(defn- parse-s [x]
  (let [[nml+key val] (string/split x #"=" 2)
        [nml key] (parse-g nml+key)]
    [nml key val]))

(def cliopts
  [["-c" "--create"     "Create new namelist"                                                              ]
   ["-e" "--edit file"  "Edit file (instead of '-i file -o file')"     :assoc-fn assoc-e                   ]
   ["-f" "--format fmt" "Output in format 'fmt' (default: namelist)"   :assoc-fn assoc-f :parse-fn parse-f ]
   ["-g" "--get n:k"    "Get value of key 'k' in namelist 'n'"         :assoc-fn assoc-g :parse-fn parse-g ]
   ["-h" "--help"       "Show usage information"                                                           ]
   ["-i" "--in file"    "Input file (default: stdin)"                  :assoc-fn assoc-i                   ]
   ["-n" "--no-prefix"  "Report values without 'namelist:key=' prefix"                                     ]
   ["-o" "--out file"   "Output file (default: stdout)"                :assoc-fn assoc-o                   ]
   ["-s" "--set n:k=v"  "Set value of key 'k' in namelist 'n' to 'v'"  :assoc-fn assoc-s :parse-fn parse-s ]
   ["-v" "--version"    "Show version information"                                                         ]])

;; main

(defn -main [& args]

  (alter-var-root #'*read-eval* (constantly false))

  ;; bindings

  (let [{:keys [options arguments summary]} (cli/parse-opts args cliopts)
        gets (:get options)
        sets (:set options)
        edit (:edit options)
        in   (or edit (:in options) *in*)
        out  (or edit (:out options) *out*)
        src  (or edit (:in options) "stdin")]

    ;; error checking

    (if (not-empty arguments) (fail (str "Unexpected argument '" (first arguments) "'")))
    (if (:help options) (usage summary))
    (if (:version options) (do (println version) (System/exit 0)))
    (if (and gets sets) (fail (msgs :get+set)))
    (if (and gets edit) (fail (msgs :get+edit)))
    (if (and edit (:in options)) (fail (msgs :edit+in)))
    (if (and edit (:out options)) (fail (msgs :edit+out)))
    (if (and gets (:format options)) (fail (msgs :get+format)))
    (if (and sets (:no-prefix options)) (fail (msgs :set+no-prefix)))
    (if (and (:create options) (:in options)) (fail (msgs :create+in)))

    ;; read -> parse -> lookup or modify -> output

    (let [fmt (let [f (:format options)] (if f (formats f) fmt-namelist))
          m   (nml-map (if (:create options) "" (read-file in)) src)]
      (cond gets  (nml-out out (nml-gets m gets (:no-prefix options)))
            sets  (nml-out out (fmt (nml-sets m sets)))
            :else (nml-out out (fmt m))))))
