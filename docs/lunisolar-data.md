# Offline lunisolar calendar data (version 1)

Data source: Hong Kong Observatory (HKO), Government of the Hong Kong Special Administrative Region, Gregorian-Lunar Calendar Conversion Table, 1901–2100, provided via DATA.GOV.HK.

- Official table: https://www.hko.gov.hk/en/gts/time/conversion.htm
- Original annual text: https://www.hko.gov.hk/tc/gts/time/calendar/text/files/T{YYYY}c.txt
- Open data: https://data.gov.hk/en-data/dataset/hk-hko-rss-gregorian-lunar-calendar-conversion-table
- Terms: https://data.gov.hk/en/terms-and-conditions
- Extracted intermediate data: https://github.com/william-drakemond/lunisolar-ephemeris/blob/56c4f70febba6bf1146b68a719fab6df9495875d/test/fixtures/hko-digest.csv.gz
- Intermediate Git blob: 45c57fbbe1fa971d2719048eb94d8c9819c3c1b5

The intellectual property rights in the Data are owned by the Government of the Hong Kong Special Administrative Region and the relevant organisations. Proper attribution is given to the Government, Hong Kong Observatory and DATA.GOV.HK. The Data is reproduced under the DATA.GOV.HK Terms of Use and is provided on an AS IS basis without warranty of any kind. It is not relicensed as Etar source code.

The intermediate gzip was decoded and checked against its CRC and size. The extracted 2,474 month starts and 4,800 solar term dates were checked for chronological ordering, legal month lengths, normal/leap month progression, and exactly 24 terms per year. The initial partial lunar month (1900-12-22, month 11) follows from the official 1901 table (1901-01-01 = day 11; 1901-01-20 = month 12 day 1). The final month is covered only through 2100-12-31. Spot checks used official HKO tables; the complete source snapshot was obtained from the pinned intermediate, not independently downloaded year by year.

The table contains calendar dates only. It does not claim exact solar-term or new-moon times. HKO warns that dates of some far-future events close to midnight can change as astronomical predictions improve. Update the version and rerun validation when replacing the data.
