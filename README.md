# Asset Management: Intelligent Capital Call & Liquidity Orchestration on Camunda 8 + Go

A Camunda 8.10 (self-managed, open source) implementation of `use_case.pdf`, ported from `../temporal-asset-management`.

```bash
make up      # start Camunda 8.10.9 + Postgres + mocks + Go worker/API
make demo    # run the scripted 10-LP capital call (GP decision in Tasklist)
make down    # stop  (make clean = stop + wipe data)
```

* Dashboard: http://localhost:8090
* Operate / Tasklist: http://localhost:8080 (demo/demo)
* Email log: http://localhost:8081

See **[demo_camunda_assetmgmt.md](demo_camunda_assetmgmt.md)** for the architecture mapping, start/stop instructions and the full demo walkthrough.
